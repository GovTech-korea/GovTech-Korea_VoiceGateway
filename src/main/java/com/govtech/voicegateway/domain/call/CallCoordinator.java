package com.govtech.voicegateway.domain.call;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.govtech.voicegateway.domain.audio.AudioSink;
import com.govtech.voicegateway.domain.audio.DelayLine;
import com.govtech.voicegateway.domain.audio.DelayLines;
import com.govtech.voicegateway.domain.call.message.ClientMessage;
import com.govtech.voicegateway.domain.call.message.ErrorCode;
import com.govtech.voicegateway.domain.call.message.ProtocolException;
import com.govtech.voicegateway.domain.call.message.ServerMessage;
import com.govtech.voicegateway.domain.call.message.ServerMessage.CallEndReason;
import com.govtech.voicegateway.domain.call.message.ServerMessage.RingCancelReason;
import com.govtech.voicegateway.domain.call.message.ServerMessage.StatusReason;
import com.govtech.voicegateway.global.config.VoiceGatewayProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 민원인 큐, 상담원 자동 배정, 벨, 통화 연결·종료를 관리한다 (docs/app-integration.md 3절).
 * <p>
 * 상태 변경은 모두 이 객체의 잠금 안에서 일어난다. 음성 프레임은 초당 50개씩 오가므로
 * 잠금 없이 {@link #audioSinks}만 보고 전달한다. 민원인 음성은 통화별 {@link DelayLine}을 거쳐
 * 설정한 시간({@code voicegateway.delay-ms})만큼 늦게 상담원에게 가고, 상담원 음성은 바로 민원인에게 간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCoordinator {

	static final int FRAME_BYTES = 640;

	private final VoiceGatewayProperties properties;
	private final RingTimer ringTimer;
	private final DelayLines delayLines;
	private final Clock clock;

	private final Map<String, Agent> agentsById = new HashMap<>();
	private final Map<String, Agent> agentsByConnection = new HashMap<>();
	private final Map<String, Call> callsById = new HashMap<>();
	private final Map<String, Call> callsByCitizenConnection = new HashMap<>();
	private final Deque<Call> queue = new ArrayDeque<>();
	/** 연결 ID → 그 연결이 보낸 음성을 받을 곳. 통화 중(connected)인 동안만 들어 있다. */
	private final Map<String, AudioSink> audioSinks = new ConcurrentHashMap<>();
	/** 통화 ID → 민원인 음성 지연 버퍼. 통화 중인 동안만 들어 있다. */
	private final Map<String, DelayLine> delayLinesByCall = new ConcurrentHashMap<>();
	private long readyOrder;

	public synchronized void handle(ClientConnection connection, ClientMessage message) {
		switch (message) {
			case ClientMessage.Register register -> register(connection, register.agentId());
			case ClientMessage.Join join -> join(connection, join.callId());
			case ClientMessage.Status status -> changeStatus(connection, status.state());
			case ClientMessage.Accept accept -> accept(connection, accept.callId());
			case ClientMessage.Reject reject -> reject(connection, reject.callId());
			case ClientMessage.Hangup hangup -> hangup(connection, hangup.callId());
		}
	}

	/**
	 * 통화 중인 상대에게 음성 프레임을 전달한다. 통화 연결 전 프레임은 규격대로 버린다.
	 */
	public void relayAudio(ClientConnection from, byte[] frame) {
		if (frame.length != FRAME_BYTES) {
			throw new ProtocolException(ErrorCode.INVALID_FRAME,
					"음성 프레임은 640바이트여야 합니다 (받은 크기: " + frame.length + ")");
		}
		AudioSink sink = audioSinks.get(from.id());
		if (sink != null) {
			sink.accept(frame);
		}
	}

	/**
	 * 통화 중인 민원인 음성 중 아직 상담원에게 나가지 않은 구간을 무음으로 바꾼다.
	 * 구간을 주지 않으면(null) 그쪽 끝은 제한하지 않는다.
	 *
	 * @return 무음 처리한 프레임 수. 통화 중인 callId가 아니면 비어 있다
	 */
	public OptionalInt mute(String callId, Long fromEpochMs, Long toEpochMs) {
		DelayLine line = delayLinesByCall.get(callId);
		if (line == null) {
			return OptionalInt.empty();
		}
		int muted = line.mute(fromEpochMs == null ? Long.MIN_VALUE : fromEpochMs,
				toEpochMs == null ? Long.MAX_VALUE : toEpochMs);
		log.info("음성 차단: {} {}프레임 [{} ~ {}]", callId, muted, fromEpochMs, toEpochMs);
		return OptionalInt.of(muted);
	}

	public synchronized void disconnected(ClientConnection connection) {
		Agent agent = agentsByConnection.remove(connection.id());
		if (agent != null) {
			agentsById.remove(agent.agentId);
			agentDisconnected(agent);
			return;
		}
		Call call = callsByCitizenConnection.get(connection.id());
		if (call != null) {
			endCall(call, CallEndReason.DISCONNECTED, false);
		}
	}

	// ---------- 상담원 ----------

	private void register(ClientConnection connection, String agentId) {
		requireUnbound(connection);
		if (agentsById.containsKey(agentId)) {
			throw new ProtocolException(ErrorCode.DUPLICATE_AGENT, agentId + "는 이미 접속해 있습니다");
		}
		Agent agent = new Agent(agentId, connection);
		becomeReady(agent);
		agentsById.put(agentId, agent);
		agentsByConnection.put(connection.id(), agent);
		connection.send(new ServerMessage.Registered(agentId, AgentState.READY));
		log.info("상담원 등록: {}", agentId);
		dispatch();
	}

	private void changeStatus(ClientConnection connection, AgentState state) {
		Agent agent = requireAgent(connection);
		if (agent.state == AgentState.RINGING || agent.state == AgentState.IN_CALL) {
			throw new ProtocolException(ErrorCode.INVALID_STATE, "벨이 울리거나 통화 중에는 상태를 바꿀 수 없습니다");
		}
		if (state == AgentState.READY) {
			// 이미 READY면 대기 순번을 그대로 둔다 (다시 보냈다고 맨 뒤로 밀리지 않게)
			if (agent.state != AgentState.READY) {
				becomeReady(agent);
			}
		} else {
			agent.state = AgentState.AWAY;
		}
		connection.send(new ServerMessage.StatusChanged(state, StatusReason.REQUESTED));
		dispatch();
	}

	private void accept(ClientConnection connection, String callId) {
		Agent agent = requireAgent(connection);
		Call call = requireRinging(agent, callId);
		cancelRing(agent);
		call.state = CallState.CONNECTED;
		agent.state = AgentState.IN_CALL;
		DelayLine delayLine = delayLines.open(Duration.ofMillis(properties.delayMs()), agent.connection::sendAudio);
		call.delayLine = delayLine;
		delayLinesByCall.put(callId, delayLine);
		audioSinks.put(call.citizen.id(), frame -> delayLine.offer(frame, delayLines.nowNanos(), clock.millis()));
		audioSinks.put(agent.connection.id(), call.citizen::sendAudio);
		call.citizen.send(new ServerMessage.Connected(callId, Role.AGENT, 0));
		agent.connection.send(new ServerMessage.Connected(callId, Role.CITIZEN, properties.delayMs()));
		log.info("통화 연결: {} ↔ {}", callId, agent.agentId);
	}

	private void reject(ClientConnection connection, String callId) {
		Agent agent = requireAgent(connection);
		Call call = requireRinging(agent, callId);
		cancelRing(agent);
		call.rejectedBy.add(agent.agentId);
		agent.call = null;
		becomeReady(agent);
		requeueAtFront(call);
		log.info("벨 거절: {} by {}", callId, agent.agentId);
		dispatch();
	}

	/**
	 * 벨 타이머 콜백. 콜백이 잠금을 기다리는 사이 같은 agentId로 재접속해 새 벨이 울릴 수 있으므로,
	 * 문자열이 아니라 벨을 울린 그 상담원 객체인지로 확인한다.
	 */
	private void ringTimedOut(String callId, Agent ringingAgent) {
		synchronized (this) {
			Call call = callsById.get(callId);
			if (call == null || call.state != CallState.RINGING || call.agent != ringingAgent) {
				return;
			}
			Agent agent = call.agent;
			String agentId = agent.agentId;
			agent.ringTimeout = null;
			agent.call = null;
			agent.state = AgentState.AWAY;
			agent.connection.send(new ServerMessage.RingCancelled(callId, RingCancelReason.TIMEOUT));
			agent.connection.send(new ServerMessage.StatusChanged(AgentState.AWAY, StatusReason.NO_ANSWER));
			requeueAtFront(call);
			log.info("벨 미응답: {} by {} → AWAY", callId, agentId);
			dispatch();
		}
	}

	private void agentDisconnected(Agent agent) {
		log.info("상담원 연결 끊김: {} ({})", agent.agentId, agent.state);
		Call call = agent.call;
		if (agent.state == AgentState.RINGING) {
			cancelRing(agent);
			requeueAtFront(call);
			dispatch();
		} else if (agent.state == AgentState.IN_CALL) {
			agent.call = null;
			endCall(call, CallEndReason.DISCONNECTED, true);
		}
	}

	// ---------- 민원인 ----------

	private void join(ClientConnection connection, String callId) {
		requireUnbound(connection);
		if (callsById.containsKey(callId)) {
			throw new ProtocolException(ErrorCode.DUPLICATE_CALL, callId + "는 이미 사용 중입니다");
		}
		Call call = new Call(callId, connection);
		callsById.put(callId, call);
		callsByCitizenConnection.put(connection.id(), call);
		queue.addLast(call);
		log.info("민원인 큐 진입: {}", callId);
		dispatch();
	}

	private void hangup(ClientConnection connection, String callId) {
		Call citizenCall = callsByCitizenConnection.get(connection.id());
		if (citizenCall != null) {
			if (!citizenCall.callId.equals(callId)) {
				throw new ProtocolException(ErrorCode.INVALID_STATE, "내 통화가 아닙니다: " + callId);
			}
			endCall(citizenCall, CallEndReason.HANGUP_CITIZEN, true);
			return;
		}
		Agent agent = requireAgent(connection);
		if (agent.state != AgentState.IN_CALL || !agent.call.callId.equals(callId)) {
			throw new ProtocolException(ErrorCode.INVALID_STATE, "통화 중이 아닙니다: " + callId);
		}
		endCall(agent.call, CallEndReason.HANGUP_AGENT, true);
	}

	// ---------- 배정 ----------

	/**
	 * 큐 앞에서부터, 그 통화를 거절하지 않은 READY 상담원 중 가장 오래 대기한 사람에게 벨을 울린다.
	 */
	private void dispatch() {
		for (var it = queue.iterator(); it.hasNext(); ) {
			Call call = it.next();
			Optional<Agent> agent = agentsById.values().stream()
					.filter(a -> a.state == AgentState.READY && !call.rejectedBy.contains(a.agentId))
					.min(Comparator.comparingLong(a -> a.readyOrder));
			if (agent.isPresent()) {
				it.remove();
				ring(call, agent.get());
			}
		}
		notifyQueuePositions();
	}

	private void ring(Call call, Agent agent) {
		call.state = CallState.RINGING;
		call.agent = agent;
		agent.state = AgentState.RINGING;
		agent.call = call;
		agent.connection.send(new ServerMessage.Ringing(call.callId, properties.ringTimeoutMs()));
		call.citizen.send(new ServerMessage.AgentRinging(call.callId));
		String callId = call.callId;
		agent.ringTimeout = ringTimer.schedule(Duration.ofMillis(properties.ringTimeoutMs()),
				() -> ringTimedOut(callId, agent));
		log.info("벨: {} → {}", callId, agent.agentId);
	}

	/** 순번이 바뀐 대기 민원인에게만 queued를 보낸다. */
	private void notifyQueuePositions() {
		int position = 1;
		for (Call call : queue) {
			if (call.notifiedPosition != position) {
				call.notifiedPosition = position;
				call.citizen.send(new ServerMessage.Queued(call.callId, position));
			}
			position++;
		}
	}

	private void requeueAtFront(Call call) {
		call.state = CallState.QUEUED;
		call.agent = null;
		call.notifiedPosition = 0;
		queue.addFirst(call);
	}

	// ---------- 종료 ----------

	/**
	 * @param citizenConnected 민원인 연결이 살아 있으면 call-ended를 보내고 연결을 닫는다
	 */
	private void endCall(Call call, CallEndReason reason, boolean citizenConnected) {
		Agent agent = call.agent;
		switch (call.state) {
			case QUEUED -> queue.remove(call);
			case RINGING -> {
				cancelRing(agent);
				agent.call = null;
				agent.state = AgentState.READY;
				agent.connection.send(new ServerMessage.RingCancelled(call.callId, RingCancelReason.CITIZEN_LEFT));
			}
			case CONNECTED -> {
				audioSinks.remove(call.citizen.id());
				delayLinesByCall.remove(call.callId);
				delayLines.close(call.delayLine);
				if (agent != null) {
					audioSinks.remove(agent.connection.id());
					if (agent.call == call && agentsById.get(agent.agentId) == agent) {
						agent.call = null;
						becomeReady(agent);
						agent.connection.send(new ServerMessage.CallEnded(call.callId, reason));
						agent.connection.send(new ServerMessage.StatusChanged(AgentState.READY, StatusReason.CALL_ENDED));
					}
				}
			}
		}
		callsById.remove(call.callId);
		callsByCitizenConnection.remove(call.citizen.id());
		if (citizenConnected) {
			call.citizen.send(new ServerMessage.CallEnded(call.callId, reason));
			call.citizen.close();
		}
		log.info("통화 종료: {} ({})", call.callId, reason);
		dispatch();
	}

	// ---------- 공통 ----------

	private void becomeReady(Agent agent) {
		agent.state = AgentState.READY;
		agent.readyOrder = ++readyOrder;
	}

	private void cancelRing(Agent agent) {
		if (agent.ringTimeout != null) {
			agent.ringTimeout.cancel();
			agent.ringTimeout = null;
		}
	}

	private void requireUnbound(ClientConnection connection) {
		if (agentsByConnection.containsKey(connection.id()) || callsByCitizenConnection.containsKey(connection.id())) {
			throw new ProtocolException(ErrorCode.INVALID_STATE, "이미 등록되었거나 전화를 건 연결입니다");
		}
	}

	private Agent requireAgent(ClientConnection connection) {
		Agent agent = agentsByConnection.get(connection.id());
		if (agent == null) {
			throw new ProtocolException(ErrorCode.INVALID_STATE, "상담원으로 등록되지 않은 연결입니다");
		}
		return agent;
	}

	private static Call requireRinging(Agent agent, String callId) {
		if (agent.state != AgentState.RINGING || !agent.call.callId.equals(callId)) {
			throw new ProtocolException(ErrorCode.INVALID_STATE, "벨이 울리고 있는 통화가 아닙니다: " + callId);
		}
		return agent.call;
	}

	private enum CallState {
		QUEUED,
		RINGING,
		CONNECTED
	}

	private static final class Agent {
		private final String agentId;
		private final ClientConnection connection;
		private AgentState state;
		private long readyOrder;
		private Call call;
		private RingTimer.Cancellable ringTimeout;

		private Agent(String agentId, ClientConnection connection) {
			this.agentId = agentId;
			this.connection = connection;
		}
	}

	private static final class Call {
		private final String callId;
		private final ClientConnection citizen;
		private final Set<String> rejectedBy = new HashSet<>();
		private CallState state = CallState.QUEUED;
		private Agent agent;
		private int notifiedPosition;
		private DelayLine delayLine;

		private Call(String callId, ClientConnection citizen) {
			this.callId = callId;
			this.citizen = citizen;
		}
	}
}
