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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import com.govtech.voicegateway.domain.audio.Announcement;
import com.govtech.voicegateway.domain.audio.Announcements;
import com.govtech.voicegateway.domain.audio.AudioHistory;
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
import com.govtech.voicegateway.domain.emotion.EmotionAnalyzer;
import com.govtech.voicegateway.domain.emotion.EmotionResult;
import com.govtech.voicegateway.domain.protection.CallProtection;
import com.govtech.voicegateway.domain.protection.KeywordRiskDetector;
import com.govtech.voicegateway.domain.protection.ProtectionPolicyStore;
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
 * 상담원 음성은 바로 민원인에게 간다. 통화가 연결되면 민원인에게 '폭언 안내' 멘트를 먼저 들려주고, 안내 멘트가 나가는 동안 상담원 음성은 보내지 않는다.
 * 폭언과 감정 격양은 통화별 {@link CallProtection}이 보호 정책대로 세고, 정책에 닿으면 안내 멘트를 틀거나 통화를 끊는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCoordinator {

	static final int FRAME_BYTES = 640;
	/** 감정 분석에 보낼 발화 음성을 꺼내려고 들고 있는 최근 민원인 음성 (30초) */
	static final int HISTORY_FRAMES = 1500;
	/** '세션 종료' 멘트가 앱에서 끝까지 재생되도록 멘트 길이보다 조금 더 기다렸다가 끊는다 */
	static final long TERMINATE_MARGIN_MS = 500;

	private final VoiceGatewayProperties properties;
	private final RingTimer ringTimer;
	private final DelayLines delayLines;
	private final SttRelay sttRelay;
	private final KeywordRiskDetector riskDetector;
	private final Announcements announcements;
	private final ProtectionPolicyStore policyStore;
	private final EmotionAnalyzer emotionAnalyzer;
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
	 * 상담원이 REST로 민원인 음성 차단 유지를 켜거나 끈다. 바뀐 상태는 상담원 앱에 mute-hold-changed로도 알린다.
	 * 끄면 오탐지 해제로 보고, 그 차단 중에 확정한 폭언을 횟수에서 뺀다. 위험 판단의 차단 시작({@code protect})과 겹치지 않게
	 * 해제부터 오탐지 처리까지 한 잠금 안에서 한다.
	 *
	 * @return 켤 때 새로 무음 처리한 대기 프레임 수 (끌 때는 0). 통화 중인 callId가 아니면 비어 있다
	 */
	public synchronized OptionalInt holdMute(String callId, boolean hold) {
		OptionalInt muted = setHold(callId, hold);
		if (muted.isPresent()) {
			notifyHold(callId);
			if (!hold) {
				stopAnnouncement(callId); // 상담원이 바로 말할 수 있게 재생 중인 안내를 멈춘다
				applyProtection(callId, CallProtection::falsePositive);
			}
		}
		return muted;
	}

	/**
	 * 지금 차단 상태를 상담원 앱에 알린다. 요청 값이 아니라 잠금 안에서 다시 읽은 실제 상태를 보내,
	 * 해제와 거의 동시에 위험 판단이 다시 켠 경우에도 마지막 알림이 실제 상태와 같게 한다.
	 */
	private synchronized void notifyHold(String callId) {
		Call call = callsById.get(callId);
		if (call != null && call.state == CallState.CONNECTED && call.agent != null) {
			call.agent.connection.send(new ServerMessage.MuteHoldChanged(callId, call.delayLine.isHold()));
		}
	}

	/**
	 * 민원인 음성 차단 유지를 켜거나 끈다. 켜 두는 동안 상담원에게는 무음만 간다 (STT에는 원본 그대로).
	 *
	 * @return 켤 때 새로 무음 처리한 대기 프레임 수 (끌 때는 0). 통화 중인 callId가 아니면 비어 있다
	 */
	private OptionalInt setHold(String callId, boolean hold) {
		DelayLine line = delayLinesByCall.get(callId);
		if (line == null) {
			return OptionalInt.empty();
		}
		int muted = line.setHold(hold);
		if (line.isClosed()) {
			return OptionalInt.empty(); // 조회와 적용 사이에 통화가 끝났다
		}
		log.info("음성 차단 유지 {}: {} (대기 프레임 {}개 무음)", hold ? "켜기" : "끄기", callId, muted);
		return OptionalInt.of(muted);
	}

	/** 재생 중인 안내를 멈춘다. '세션 종료' 멘트는 통화를 끊기 전 끝까지 들려준다 */
	private synchronized void stopAnnouncement(String callId) {
		Call call = callsById.get(callId);
		if (call != null && call.announcement != null && !(call.protection != null && call.protection.isTerminating())) {
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
		call.connectedAtEpochMs = clock.millis();
		call.protection = new CallProtection(callId, policyStore.get());
		AudioHistory history = new AudioHistory(HISTORY_FRAMES);
		call.history = history;
		SttRelay.Session stt = sttRelay.open(callId, new CallSttListener(call, agent.connection));
		call.stt = stt;
		audioSinks.put(call.citizen.id(), frame -> {
			long receivedAt = clock.millis();
			delayLine.offer(frame, delayLines.nowNanos(), receivedAt);
			stt.accept(frame, receivedAt);
			history.add(frame, receivedAt);
		});
		call.citizen.send(new ServerMessage.Connected(callId, Role.AGENT, 0));
		agent.connection.send(new ServerMessage.Connected(callId, Role.CITIZEN, properties.delayMs()));
		agent.connection.send(protectionState(call.protection.state()));
		// 안내를 먼저 시작한 뒤 상담원 음성 경로를 연다 (그 사이 상담원 음성이 안내 전에 새어 나가지 않게)
		play(call, announcements.abuseNotice(), "폭언 안내");
		audioSinks.put(agent.connection.id(), frame -> {
			if (!call.isAnnouncing()) { // 안내 음성과 섞이지 않게 그동안 상담원 음성은 버린다
				call.citizen.sendAudio(frame);
			}
		});
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
				if (call.terminateTimer != null) {
					call.terminateTimer.cancel();
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

	// ---------- 보호 정책 ----------

	private synchronized void applyProtection(String callId, Function<CallProtection, CallProtection.Result> step) {
		Call call = callsById.get(callId);
		if (call != null && call.state == CallState.CONNECTED) {
			applyProtection(call, step);
		}
	}

	/**
	 * 보호 정책 한 단계를 적용하고, 생긴 보호 기록과 바뀐 카운터를 상담원에게 보낸 뒤 정해진 안내 멘트를 튼다.
	 *
	 * @return 틀기로 한 안내 멘트 (없으면 NONE)
	 */
	private synchronized CallProtection.Action applyProtection(Call call, Function<CallProtection, CallProtection.Result> step) {
		if (!isConnected(call)) {
			return CallProtection.Action.NONE;
		}
		CallProtection.State before = call.protection.state();
		CallProtection.Result result = step.apply(call.protection);
		ClientConnection agentConnection = call.agent.connection;
		for (CallProtection.Event event : result.events()) {
			agentConnection.send(new ServerMessage.ProtectionEvent(call.callId, event.eventId(), event.kind(),
					event.startMs(), event.endMs(), event.text(), event.keyword()));
		}
		CallProtection.State after = call.protection.state();
		if (!after.equals(before)) {
			agentConnection.send(protectionState(after));
		}
		switch (result.action()) {
			case NONE -> {
			}
			case AFTER_NOTICE -> play(call, announcements.afterNotice(), "폭언 후 안내");
			case SESSION_END -> {
				play(call, announcements.sessionEnd(), "세션 종료");
				long waitMs = announcements.sessionEnd().durationMs() + TERMINATE_MARGIN_MS;
				call.terminateTimer = ringTimer.schedule(Duration.ofMillis(waitMs), () -> terminateByPolicy(call));
			}
		}
		return result.action();
	}

	/** 지금 재생 중인 안내를 멈추고 새 안내를 처음부터 튼다 */
	private void play(Call call, Announcement announcement, String name) {
		if (call.announcement != null) {
			call.announcement.stop();
		}
		if (announcement.frames().isEmpty()) {
			return;
		}
		call.announcement = delayLines.play(announcement.frames(), call.citizen::sendAudio);
		log.info("민원인 안내 멘트 재생: {} {} ({}ms)", call.callId, name, announcement.durationMs());
	}

	private synchronized void terminateByPolicy(Call call) {
		if (isConnected(call)) {
			endCall(call, CallEndReason.POLICY_TERMINATED, true);
		}
	}

	private static ServerMessage.ProtectionState protectionState(CallProtection.State state) {
		return new ServerMessage.ProtectionState(state.callId(), state.abuseCount(), state.abuseLimit(),
				state.afterNoticeCount(), state.afterNoticeLimit(), state.agitationSeconds(), state.agitationLimitSeconds());
	}

	/** 통화 연결부터 잰 ms. 시각을 모르면 지금 */
	private long sinceConnected(Call call, Long epochMs) {
		long at = epochMs == null ? clock.millis() : epochMs;
		return Math.max(0, at - call.connectedAtEpochMs);
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
		/** 앞 발화가 끝난 시각. 처음에는 통화 연결 시각 (STT 결과 스레드에서만 쓴다) */
		private long lastUtteranceEndMs;
		/** 감정 분석을 발화 순서대로 하나씩 하도록 이어 붙이는 꼬리 (STT 결과 스레드에서만 쓴다) */
		private CompletableFuture<Void> emotionTail = CompletableFuture.completedFuture(null);
		/** 마지막으로 센 폭언이 끝난 시각 (잠금 안에서만 쓴다) */
		private Long lastAbuseToMs;

		private CallSttListener(Call call, ClientConnection agentConnection) {
			this.call = call;
			this.agentConnection = agentConnection;
			this.lastUtteranceEndMs = call.connectedAtEpochMs;
		}

		@Override
		public void onTranscript(SttTranscript transcript) {
			if (!isConnected(call)) {
				return;
			}
			sendIfConnected(call, agentConnection, new ServerMessage.Transcript(call.callId, transcript.utteranceId(),
					transcript.text(), transcript.isFinal()));
			riskDetector.detect(transcript.text(), transcript.isFinal()).ifPresent(match -> protect(transcript.utteranceId(), transcript.text(),
					transcript.fromEpochMs(), transcript.toEpochMs(), match.riskType(), match.severity(), match.keyword()));
			if (transcript.isFinal()) {
				analyzeEmotion(transcript);
			}
		}

		/**
		 * 끝난 발화의 음성을 감정 분석에 보낸다. 발화 시작 시각을 모르면 앞 발화가 끝난 뒤부터로 본다.
		 * 결과는 상담원에게 emotion으로 보내고, 격양이 이어진 시간은 보호 정책이 센다.
		 */
		private void analyzeEmotion(SttTranscript transcript) {
			long to = transcript.toEpochMs() == null ? clock.millis() : transcript.toEpochMs();
			long from = transcript.fromEpochMs() != null ? transcript.fromEpochMs() : lastUtteranceEndMs;
			lastUtteranceEndMs = to + 1;
			byte[] pcm = call.history.between(from, to);
			if (pcm.length == 0) {
				return;
			}
			long startMs = sinceConnected(call, from);
			long endMs = startMs + pcm.length / (FRAME_BYTES / 20);
			// 앞 발화 결과가 나온 뒤에 보낸다 (결과 순서가 바뀌면 격양이 이어진 시간이 틀어진다)
			emotionTail = emotionTail
					.thenCompose(ignored -> isConnected(call)
							? emotionAnalyzer.analyze(pcm, transcript.text())
							: CompletableFuture.<EmotionResult>completedFuture(null))
					.thenAccept(result -> {
						if (result != null) {
							onEmotion(result, startMs, endMs, transcript.text());
						}
					})
					.exceptionally(e -> {
						log.warn("감정 분석 실패: {} {}", call.callId, e.toString());
						return null;
					});
		}

		private void onEmotion(EmotionResult result, long startMs, long endMs, String text) {
			synchronized (CallCoordinator.this) {
				if (!isConnected(call)) {
					return;
				}
				agentConnection.send(new ServerMessage.Emotion(call.callId, result.score(), result.agitated()));
				applyProtection(call, protection -> protection.emotion(result.agitated(), startMs, endMs, text));
			}
		}

		@Override
		public void onRisk(SttRisk risk) {
			if (isConnected(call)) {
				protect("risk-" + risk.fromEpochMs() + "-" + risk.toEpochMs(), risk.text(), risk.fromEpochMs(),
						risk.toEpochMs(), risk.riskType(), risk.severity(), null);
			}
		}

		@Override
		public void onHold(boolean on) {
			if (isConnected(call) && setHold(call.callId, on).isPresent()) {
				if (!on) {
					call.protection.holdReleased();
				}
				sendIfConnected(call, agentConnection, new ServerMessage.MuteHoldChanged(call.callId, on));
			}
		}

		/**
		 * 처음 찾은 위험 발화면 그 발화 시작부터 아직 안 나간 부분을 지우고 차단 유지를 켠다.
		 * 욕한 민원인은 다음 말도 폭언일 가능성이 높아, 상담원이 직접 풀 때까지 민원인 음성을 계속 막는다.
		 * 같은 발화의 결과가 더 와도 다시 지우거나 켜지 않는다 (상담원이 푼 뒤 바로 다시 막히지 않게).
		 */
		private void protect(String key, String text, Long fromEpochMs, Long toEpochMs, String riskType, String severity,
				String keyword) {
			if (!notified.add(key)) {
				return;
			}
			// 차단 시작과 폭언 등록을 한 잠금 안에서 해, 상담원의 오탐지 해제가 그 사이에 끼지 않게 한다
			synchronized (CallCoordinator.this) {
				DelayLine line = delayLinesByCall.get(call.callId);
				if (line == null || !isConnected(call)) {
					return;
				}
				DelayLine.HoldStart hold = line.startHold(fromEpochMs == null ? Long.MIN_VALUE : fromEpochMs);
				if (line.isClosed()) {
					return; // 그 사이 통화가 끝났다
				}
				// 발화 본문은 개인정보를 담을 수 있어 로그에는 유형과 찾은 단어만 남긴다
				log.info("위험 발화 감지: {} {} {} '{}' (무음 {}프레임, 차단 유지)", call.callId, riskType, severity, keyword,
						hold.muted());
				agentConnection.send(new ServerMessage.RiskDetected(call.callId, key, text, riskType, severity, keyword,
						hold.muted()));
				if (!hold.wasHeld()) {
					agentConnection.send(new ServerMessage.MuteHoldChanged(call.callId, true));
				}
				if (countsAsAbuse(fromEpochMs, toEpochMs, riskType)) {
					applyProtection(call, protection -> protection.abuse(sinceConnected(call, fromEpochMs),
							sinceConnected(call, toEpochMs), text, keyword));
				}
			}
		}

		/**
		 * 폭언 1회로 셀지. 같은 발화를 키워드 감지와 STT 위험 판단이 함께 알려도 한 번만 세도록,
		 * 앞서 센 폭언과 시간이 겹치면 세지 않는다. 위험이 아니라는 판단(NORMAL, NONE)도 세지 않는다.
		 */
		private boolean countsAsAbuse(Long fromEpochMs, Long toEpochMs, String riskType) {
			if (riskType == null || riskType.equals("NORMAL") || riskType.equals("NONE")) {
				return false;
			}
			boolean overlaps = fromEpochMs != null && lastAbuseToMs != null && fromEpochMs <= lastAbuseToMs;
			long to = toEpochMs != null ? toEpochMs : clock.millis();
			lastAbuseToMs = lastAbuseToMs == null ? to : Math.max(lastAbuseToMs, to);
			return !overlaps;
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
		private CallProtection protection;
		private AudioHistory history;
		private RingTimer.Cancellable terminateTimer;
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
