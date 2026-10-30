package com.govtech.voicegateway.domain.call;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.govtech.voicegateway.domain.audio.Announcement;
import com.govtech.voicegateway.domain.audio.AudioSink;
import com.govtech.voicegateway.domain.audio.DelayLine;
import com.govtech.voicegateway.domain.audio.DelayLines;
import com.govtech.voicegateway.domain.audio.Playback;
import com.govtech.voicegateway.domain.call.message.ClientMessage;
import com.govtech.voicegateway.domain.call.message.ErrorCode;
import com.govtech.voicegateway.domain.call.message.ProtocolException;
import com.govtech.voicegateway.domain.call.message.ServerMessage;
import com.govtech.voicegateway.domain.call.message.ServerMessage.CallEndReason;
import com.govtech.voicegateway.domain.call.message.ServerMessage.RingCancelReason;
import com.govtech.voicegateway.domain.call.message.ServerMessage.StatusReason;
import com.govtech.voicegateway.domain.protection.KeywordRiskDetector;
import com.govtech.voicegateway.domain.stt.SttListener;
import com.govtech.voicegateway.domain.stt.SttRelay;
import com.govtech.voicegateway.domain.stt.SttRisk;
import com.govtech.voicegateway.domain.stt.SttTranscript;
import com.govtech.voicegateway.global.config.VoiceGatewayProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 민원인 큐, 상담원 자동 배정, 벨, 통화 연결·종료를 관리한다 (docs/app-integration.md 3절).
 * <p>
 * 상태 변경은 모두 이 객체의 잠금 안에서 일어난다. 음성 프레임은 초당 50개씩 오가므로
 * 잠금 없이 {@link #audioSinks}만 보고 전달한다. 민원인 음성은 받는 즉시 STT로 보내고({@link SttRelay}),
 * 통화별 {@link DelayLine}을 거쳐 설정한 시간({@code voicegateway.delay-ms})만큼 늦게 상담원에게 간다.
 * 상담원 음성은 바로 민원인에게 간다. 위험 발화로 차단을 시작하면 민원인에게 안내 음성을 들려주고, 그동안 상담원 음성은 보내지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCoordinator {

	static final int FRAME_BYTES = 640;

	private final VoiceGatewayProperties properties;
	private final RingTimer ringTimer;
	private final DelayLines delayLines;
	private final SttRelay sttRelay;
	private final KeywordRiskDetector riskDetector;
	private final Announcement riskHoldAnnouncement;
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
		if (line.isClosed()) {
			return OptionalInt.empty(); // 조회와 적용 사이에 통화가 끝났다
		}
		log.info("음성 차단: {} {}프레임 [{} ~ {}]", callId, muted, fromEpochMs, toEpochMs);
		return OptionalInt.of(muted);
	}

	/**
	 * 민원인 음성 차단 유지를 켜거나 끈다. 켜 두는 동안 상담원에게는 무음만 간다 (STT에는 원본 그대로).
	 * 끄면 민원인에게 재생 중인 차단 안내도 멈춰, 상담원이 바로 말할 수 있게 한다.
	 *
	 * @return 켤 때 새로 무음 처리한 대기 프레임 수 (끌 때는 0). 통화 중인 callId가 아니면 비어 있다
	 */
	public OptionalInt holdMute(String callId, boolean hold) {
		DelayLine line = delayLinesByCall.get(callId);
		if (line == null) {
			return OptionalInt.empty();
		}
		int muted = line.setHold(hold);
		if (line.isClosed()) {
			return OptionalInt.empty(); // 조회와 적용 사이에 통화가 끝났다
		}
		if (!hold) {
			stopAnnouncement(callId);
		}
		log.info("음성 차단 유지 {}: {} (대기 프레임 {}개 무음)", hold ? "켜기" : "끄기", callId, muted);
		return OptionalInt.of(muted);
	}

	private synchronized void stopAnnouncement(String callId) {
		Call call = callsById.get(callId);
		if (call != null && call.announcement != null) {
			call.announcement.stop();
		}
	}

	/** 테스트 콘솔·운영 확인용 현재 상태 */
	public synchronized CallCenterSnapshot snapshot() {
		List<CallCenterSnapshot.AgentView> agents = agentsById.values().stream()
				.sorted(Comparator.comparing(agent -> agent.agentId))
				.map(agent -> new CallCenterSnapshot.AgentView(agent.agentId, agent.state,
						agent.call == null ? null : agent.call.callId))
				.toList();
		List<String> queued = queue.stream().map(call -> call.callId).toList();
		List<CallCenterSnapshot.CallView> calls = callsById.values().stream()
				.sorted(Comparator.comparing(call -> call.callId))
				.map(CallCoordinator::view)
				.toList();
		return new CallCenterSnapshot(agents, queued, calls);
	}

	private static CallCenterSnapshot.CallView view(Call call) {
		boolean connected = call.state == CallState.CONNECTED;
		return new CallCenterSnapshot.CallView(
				call.callId,
				call.state.name(),
				call.agent == null ? null : call.agent.agentId,
				connected ? call.connectedAtEpochMs : null,
				connected ? call.stt.chunkCount() : 0,
				connected ? call.stt.byteCount() : 0,
				connected ? call.stt.failureCount() : 0,
				connected ? call.delayLine.mutedCount() : 0,
				connected && call.delayLine.isHold(),
				connected ? call.delayLine.pendingCount() : 0);
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
		SttRelay.Session stt = sttRelay.open(callId, new CallSttListener(call, agent.connection));
		call.stt = stt;
		call.connectedAtEpochMs = clock.millis();
		audioSinks.put(call.citizen.id(), frame -> {
			long receivedAt = clock.millis();
			delayLine.offer(frame, delayLines.nowNanos(), receivedAt);
			stt.accept(frame, receivedAt);
		});
		audioSinks.put(agent.connection.id(), frame -> {
			if (!call.isAnnouncing()) { // 안내 음성과 섞이지 않게 그동안 상담원 음성은 버린다
				call.citizen.sendAudio(frame);
			}
		});
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
				if (call.announcement != null) {
					call.announcement.stop();
				}
				call.stt.close();
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

	private synchronized boolean isConnected(Call call) {
		return callsById.get(call.callId) == call && call.state == CallState.CONNECTED;
	}

	/** 통화 중일 때만 보낸다. 확인과 보내기를 한 잠금 안에서 해서, 통화 종료 알림 뒤에 늦은 메시지가 가지 않게 한다 */
	private synchronized void sendIfConnected(Call call, ClientConnection connection, ServerMessage message) {
		if (isConnected(call)) {
			connection.send(message); // 대기열에 넣기만 해서 잠금 안에서 불러도 막히지 않는다
		}
	}

	/**
	 * 통화 하나의 STT 결과 처리 (STT 연결 스레드에서 불린다).
	 * 자막은 상담원에게 보내고, 위험 발화면 그 발화 시작부터 아직 상담원에게 나가지 않은 부분을 지우고 차단 유지를 켠 뒤
	 * 상담원에게 한 번 알린다. 차단 유지는 상담원이 풀 때까지 이어진다. 발화 시작 시각을 모르면 대기 중인 음성 전부를 지운다.
	 */
	private final class CallSttListener implements SttListener {

		private final Call call;
		private final ClientConnection agentConnection;
		private final Set<String> notified = ConcurrentHashMap.newKeySet();

		private CallSttListener(Call call, ClientConnection agentConnection) {
			this.call = call;
			this.agentConnection = agentConnection;
		}

		@Override
		public void onTranscript(SttTranscript transcript) {
			if (!isConnected(call)) {
				return;
			}
			sendIfConnected(call, agentConnection, new ServerMessage.Transcript(call.callId, transcript.utteranceId(),
					transcript.text(), transcript.isFinal()));
			riskDetector.detect(transcript.text()).ifPresent(match -> protect(transcript.utteranceId(), transcript.text(),
					transcript.fromEpochMs(), match.riskType(), match.severity(), null, match.keyword()));
		}

		@Override
		public void onRisk(SttRisk risk) {
			if (isConnected(call)) {
				protect("risk-" + risk.fromEpochMs() + "-" + risk.toEpochMs(), risk.text(), risk.fromEpochMs(),
						risk.riskType(), risk.severity(), risk.target(), null);
			}
		}

		@Override
		public void onHold(boolean on) {
			if (isConnected(call) && holdMute(call.callId, on).isPresent()) {
				sendIfConnected(call, agentConnection, new ServerMessage.MuteHoldChanged(call.callId, on));
			}
		}

		/**
		 * 처음 찾은 위험 발화면 그 발화 시작부터 아직 안 나간 부분을 지우고 차단 유지를 켠다.
		 * 욕한 민원인은 다음 말도 폭언일 가능성이 높아, 상담원이 직접 풀 때까지 민원인 음성을 계속 막는다.
		 * 같은 발화의 결과가 더 와도 다시 지우거나 켜지 않는다 (상담원이 푼 뒤 바로 다시 막히지 않게).
		 */
		private void protect(String key, String text, Long fromEpochMs, String riskType, String severity, String target,
				String keyword) {
			if (!notified.add(key)) {
				return;
			}
			DelayLine line = delayLinesByCall.get(call.callId);
			if (line == null) {
				return;
			}
			DelayLine.HoldStart hold = line.startHold(fromEpochMs == null ? Long.MIN_VALUE : fromEpochMs);
			if (line.isClosed()) {
				return; // 그 사이 통화가 끝났다
			}
			// 발화 본문은 개인정보를 담을 수 있어 로그에는 유형과 찾은 단어만 남긴다
			log.info("위험 발화 감지: {} {} {} '{}' (무음 {}프레임, 차단 유지)", call.callId, riskType, severity, keyword,
					hold.muted());
			sendIfConnected(call, agentConnection, new ServerMessage.RiskDetected(call.callId, key, text, riskType,
					severity, target, keyword, hold.muted()));
			if (!hold.wasHeld()) {
				sendIfConnected(call, agentConnection, new ServerMessage.MuteHoldChanged(call.callId, true));
				announce();
			}
		}

		/**
		 * 민원인에게 차단 안내 음성을 들려준다. 통화 종료·차단 해제와 겹치지 않게 상태 잠금 안에서 시작한다
		 * (해제는 차단 유지를 먼저 끈 뒤 이 잠금에서 안내를 멈추므로, 그 사이에 오면 여기서 꺼진 것을 보고 시작하지 않는다).
		 */
		private void announce() {
			synchronized (CallCoordinator.this) {
				DelayLine line = delayLinesByCall.get(call.callId);
				if (!isConnected(call) || line == null || !line.isHold() || call.isAnnouncing()
						|| riskHoldAnnouncement.frames().isEmpty()) {
					return;
				}
				call.announcement = delayLines.play(riskHoldAnnouncement.frames(), call.citizen::sendAudio);
			}
			log.info("민원인 안내 음성 재생: {} ({}ms)", call.callId, riskHoldAnnouncement.durationMs());
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
		private SttRelay.Session stt;
		private long connectedAtEpochMs;
		/** 민원인에게 재생 중인 안내 음성. 상담원 음성 전달 스레드가 읽으므로 volatile */
		private volatile Playback announcement;

		private Call(String callId, ClientConnection citizen) {
			this.callId = callId;
			this.citizen = citizen;
		}

		private boolean isAnnouncing() {
			Playback playback = announcement;
			return playback != null && playback.isPlaying();
		}
	}
}
