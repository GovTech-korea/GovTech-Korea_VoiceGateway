package com.govtech.voicegateway.domain.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.govtech.voicegateway.domain.audio.Announcement;
import com.govtech.voicegateway.domain.audio.DelayLines;
import com.govtech.voicegateway.domain.call.message.ClientMessage;
import com.govtech.voicegateway.domain.call.message.ErrorCode;
import com.govtech.voicegateway.domain.call.message.ProtocolException;
import com.govtech.voicegateway.domain.call.message.ServerMessage;
import com.govtech.voicegateway.domain.call.message.ServerMessage.CallEndReason;
import com.govtech.voicegateway.domain.call.message.ServerMessage.RingCancelReason;
import com.govtech.voicegateway.domain.call.message.ServerMessage.StatusReason;
import com.govtech.voicegateway.domain.protection.KeywordRiskDetector;
import com.govtech.voicegateway.domain.stt.FakeSttGateway;
import com.govtech.voicegateway.domain.stt.SttAudioFormat;
import com.govtech.voicegateway.domain.stt.SttChunk;
import com.govtech.voicegateway.domain.stt.SttListener;
import com.govtech.voicegateway.domain.stt.SttRisk;
import com.govtech.voicegateway.domain.stt.SttTranscript;
import com.govtech.voicegateway.domain.stt.SttProperties;
import com.govtech.voicegateway.domain.stt.SttRelay;
import com.govtech.voicegateway.global.config.VoiceGatewayProperties;
import com.govtech.voicegateway.support.ManualClock;

class CallCoordinatorTest {

	private static final long DELAY_MS = 800;
	private static final long RING_TIMEOUT_MS = 20_000;

	private FakeRingTimer ringTimer;
	private ManualClock clock;
	private DelayLines delayLines;
	private FakeSttGateway stt;
	private CallCoordinator coordinator;

	@BeforeEach
	void setUp() {
		ringTimer = new FakeRingTimer();
		clock = new ManualClock();
		delayLines = new DelayLines(clock::nanos);
		stt = new FakeSttGateway();
		SttRelay sttRelay = new SttRelay(new SttProperties(SttAudioFormat.PCM16_16K, null, null), java.util.List.of(stt));
		coordinator = new CallCoordinator(new VoiceGatewayProperties(DELAY_MS, RING_TIMEOUT_MS), ringTimer, delayLines,
				sttRelay, new KeywordRiskDetector(), new Announcement(List.of(frame(8), frame(9))), clock);
	}

	/** 시간을 진행시키고 지연 버퍼에서 때가 된 음성을 내보낸다. */
	private void elapse(long millis) {
		clock.advance(Duration.ofMillis(millis));
		delayLines.drainAll();
	}

	private FakeConnection agent(String agentId) {
		FakeConnection connection = new FakeConnection();
		coordinator.handle(connection, new ClientMessage.Register(agentId));
		return connection;
	}

	private FakeConnection citizen(String callId) {
		FakeConnection connection = new FakeConnection();
		coordinator.handle(connection, new ClientMessage.Join(callId));
		return connection;
	}

	private static byte[] frame(int value) {
		byte[] frame = new byte[CallCoordinator.FRAME_BYTES];
		java.util.Arrays.fill(frame, (byte) value);
		return frame;
	}

	@Nested
	@DisplayName("상담원 등록")
	class Register {

		@Test
		@DisplayName("등록하면 READY로 registered를 받는다")
		void registered() {
			FakeConnection agent = agent("agent-01");

			assertThat(agent.last()).isEqualTo(new ServerMessage.Registered("agent-01", AgentState.READY));
		}

		@Test
		@DisplayName("이미 READY인 상담원이 READY를 다시 보내도 대기 순번을 잃지 않는다")
		void readyAgainKeepsOrder() {
			FakeConnection older = agent("agent-old");
			FakeConnection newer = agent("agent-new");
			coordinator.handle(older, new ClientMessage.Status(AgentState.READY));

			citizen("call-1");

			assertThat(older.last()).isInstanceOf(ServerMessage.Ringing.class);
			assertThat(newer.last()).isInstanceOf(ServerMessage.Registered.class);
		}

		@Test
		@DisplayName("같은 agentId로 두 번 접속하면 DUPLICATE_AGENT")
		void duplicateAgent() {
			agent("agent-01");

			assertThatThrownBy(() -> agent("agent-01"))
					.isInstanceOfSatisfying(ProtocolException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.DUPLICATE_AGENT));
		}

		@Test
		@DisplayName("상태를 AWAY로 바꾸면 배정되지 않고, READY로 돌아오면 배정된다")
		void awayIsNotAssigned() {
			FakeConnection agent = agent("agent-01");
			coordinator.handle(agent, new ClientMessage.Status(AgentState.AWAY));
			assertThat(agent.last()).isEqualTo(new ServerMessage.StatusChanged(AgentState.AWAY, StatusReason.REQUESTED));

			FakeConnection citizen = citizen("call-1");
			assertThat(citizen.last()).isEqualTo(new ServerMessage.Queued("call-1", 1));

			coordinator.handle(agent, new ClientMessage.Status(AgentState.READY));
			assertThat(agent.last()).isEqualTo(new ServerMessage.Ringing("call-1", RING_TIMEOUT_MS));
			assertThat(citizen.last()).isEqualTo(new ServerMessage.AgentRinging("call-1"));
		}
	}

	@Nested
	@DisplayName("큐와 자동 배정")
	class Queue {

		@Test
		@DisplayName("대기 상담원이 있으면 바로 벨이 울리고 민원인은 agent-ringing을 받는다")
		void ringsImmediately() {
			FakeConnection agent = agent("agent-01");
			FakeConnection citizen = citizen("call-1");

			assertThat(agent.last()).isEqualTo(new ServerMessage.Ringing("call-1", RING_TIMEOUT_MS));
			assertThat(citizen.messages).containsExactly(new ServerMessage.AgentRinging("call-1"));
			assertThat(ringTimer.lastDelay()).isEqualTo(Duration.ofMillis(RING_TIMEOUT_MS));
		}

		@Test
		@DisplayName("대기 상담원이 없으면 먼저 온 순서로 순번을 받고, 앞 사람이 배정되면 순번이 바뀐다")
		void queuedInOrder() {
			FakeConnection first = citizen("call-1");
			FakeConnection second = citizen("call-2");
			assertThat(first.last()).isEqualTo(new ServerMessage.Queued("call-1", 1));
			assertThat(second.last()).isEqualTo(new ServerMessage.Queued("call-2", 2));

			agent("agent-01");

			assertThat(first.last()).isEqualTo(new ServerMessage.AgentRinging("call-1"));
			assertThat(second.last()).isEqualTo(new ServerMessage.Queued("call-2", 1));
		}

		@Test
		@DisplayName("가장 오래 대기한 상담원에게 먼저 배정한다")
		void longestIdleAgentFirst() {
			FakeConnection older = agent("agent-old");
			FakeConnection newer = agent("agent-new");

			citizen("call-1");

			assertThat(older.last()).isInstanceOf(ServerMessage.Ringing.class);
			assertThat(newer.last()).isInstanceOf(ServerMessage.Registered.class);
		}

		@Test
		@DisplayName("같은 callId로 전화를 걸면 DUPLICATE_CALL")
		void duplicateCall() {
			citizen("call-1");

			assertThatThrownBy(() -> citizen("call-1"))
					.isInstanceOfSatisfying(ProtocolException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.DUPLICATE_CALL));
		}
	}

	@Nested
	@DisplayName("벨")
	class Ring {

		@Test
		@DisplayName("받으면 양쪽에 connected가 가고, 상담원 쪽 delayMs는 설정값이다")
		void accept() {
			FakeConnection agent = agent("agent-01");
			FakeConnection citizen = citizen("call-1");

			coordinator.handle(agent, new ClientMessage.Accept("call-1"));

			assertThat(agent.last()).isEqualTo(new ServerMessage.Connected("call-1", Role.CITIZEN, DELAY_MS));
			assertThat(citizen.last()).isEqualTo(new ServerMessage.Connected("call-1", Role.AGENT, 0));
			assertThat(ringTimer.activeCount()).isZero();
		}

		@Test
		@DisplayName("20초 안에 안 받으면 벨이 멈추고 상담원은 AWAY, 민원인은 다음 상담원에게 배정된다")
		void timeout() {
			FakeConnection first = agent("agent-01");
			FakeConnection second = agent("agent-02");
			FakeConnection citizen = citizen("call-1");

			ringTimer.fireAll();

			assertThat(first.messages).containsSubsequence(
					new ServerMessage.RingCancelled("call-1", RingCancelReason.TIMEOUT),
					new ServerMessage.StatusChanged(AgentState.AWAY, StatusReason.NO_ANSWER));
			assertThat(second.last()).isEqualTo(new ServerMessage.Ringing("call-1", RING_TIMEOUT_MS));
			assertThat(citizen.last()).isEqualTo(new ServerMessage.AgentRinging("call-1"));
		}

		@Test
		@DisplayName("미응답 후 다른 상담원이 없으면 민원인은 큐 맨 앞(1번)으로 돌아간다")
		void timeoutWithoutOtherAgent() {
			agent("agent-01");
			FakeConnection citizen = citizen("call-1");
			FakeConnection later = citizen("call-2");

			ringTimer.fireAll();

			assertThat(citizen.last()).isEqualTo(new ServerMessage.Queued("call-1", 1));
			assertThat(later.last()).isEqualTo(new ServerMessage.Queued("call-2", 2));
		}

		@Test
		@DisplayName("거절하면 다음 상담원에게 넘어가고, 거절한 상담원은 READY를 유지한다")
		void reject() {
			FakeConnection first = agent("agent-01");
			FakeConnection second = agent("agent-02");
			citizen("call-1");

			coordinator.handle(first, new ClientMessage.Reject("call-1"));

			assertThat(second.last()).isEqualTo(new ServerMessage.Ringing("call-1", RING_TIMEOUT_MS));
			FakeConnection next = citizen("call-2");
			assertThat(first.last()).isEqualTo(new ServerMessage.Ringing("call-2", RING_TIMEOUT_MS));
			assertThat(next.last()).isEqualTo(new ServerMessage.AgentRinging("call-2"));
		}

		@Test
		@DisplayName("거절한 상담원에게는 같은 통화의 벨을 다시 울리지 않는다")
		void noRingAgainAfterReject() {
			FakeConnection only = agent("agent-01");
			FakeConnection citizen = citizen("call-1");

			coordinator.handle(only, new ClientMessage.Reject("call-1"));

			assertThat(only.all(ServerMessage.Ringing.class)).hasSize(1);
			assertThat(citizen.last()).isEqualTo(new ServerMessage.Queued("call-1", 1));
		}

		@Test
		@DisplayName("벨이 울리는 중에 민원인이 끊으면 상담원에게 ring-cancelled(CITIZEN_LEFT)")
		void citizenLeavesWhileRinging() {
			FakeConnection agent = agent("agent-01");
			FakeConnection citizen = citizen("call-1");

			coordinator.handle(citizen, new ClientMessage.Hangup("call-1"));

			assertThat(agent.last()).isEqualTo(new ServerMessage.RingCancelled("call-1", RingCancelReason.CITIZEN_LEFT));
			assertThat(citizen.last()).isEqualTo(new ServerMessage.CallEnded("call-1", CallEndReason.HANGUP_CITIZEN));
			assertThat(citizen.closed).isTrue();
			assertThat(ringTimer.activeCount()).isZero();
		}

		@Test
		@DisplayName("벨이 울리지 않았는데 accept하면 INVALID_STATE")
		void acceptWithoutRinging() {
			FakeConnection agent = agent("agent-01");

			assertThatThrownBy(() -> coordinator.handle(agent, new ClientMessage.Accept("call-x")))
					.isInstanceOfSatisfying(ProtocolException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE));
		}
	}

	@Nested
	@DisplayName("통화")
	class InCall {

		private FakeConnection agent;
		private FakeConnection citizen;

		@BeforeEach
		void connect() {
			agent = agent("agent-01");
			citizen = citizen("call-1");
			coordinator.handle(agent, new ClientMessage.Accept("call-1"));
			agent.clear();
			citizen.clear();
		}

		@Test
		@DisplayName("상담원 음성은 바로, 민원인 음성은 지연 시간(800ms) 뒤에 상대에게 간다")
		void relaysAudioWithCitizenDelay() {
			coordinator.relayAudio(citizen, frame(1));
			coordinator.relayAudio(agent, frame(2));

			assertThat(citizen.audio).containsExactly(frame(2));
			elapse(DELAY_MS - 1);
			assertThat(agent.audio).isEmpty();
			elapse(1);
			assertThat(agent.audio).containsExactly(frame(1));
		}

		@Test
		@DisplayName("차단하면 아직 상담원에게 나가지 않은 민원인 음성이 무음으로 간다")
		void mutePending() {
			coordinator.relayAudio(citizen, frame(1));
			elapse(20);
			coordinator.relayAudio(citizen, frame(2));

			assertThat(coordinator.mute("call-1", null, null)).hasValue(2);
			elapse(DELAY_MS);

			assertThat(agent.audio).containsExactly(new byte[640], new byte[640]);
		}

		@Test
		@DisplayName("구간을 지정하면 그 시각에 받은 민원인 음성만 무음이 된다")
		void muteRange() {
			long first = clock.millis();
			coordinator.relayAudio(citizen, frame(1));
			elapse(20);
			long second = clock.millis();
			coordinator.relayAudio(citizen, frame(2));
			elapse(20);
			coordinator.relayAudio(citizen, frame(3));

			assertThat(coordinator.mute("call-1", second, second)).hasValue(1);
			elapse(DELAY_MS);

			assertThat(first).isLessThan(second);
			assertThat(agent.audio).containsExactly(frame(1), new byte[640], frame(3));
		}

		@Test
		@DisplayName("통화가 끝나면 지연 중이던 민원인 음성은 버린다")
		void dropsPendingOnHangup() {
			coordinator.relayAudio(citizen, frame(1));
			coordinator.handle(citizen, new ClientMessage.Hangup("call-1"));
			elapse(DELAY_MS);

			assertThat(agent.audio).isEmpty();
			assertThat(coordinator.mute("call-1", null, null)).isEmpty();
		}

		@Test
		@DisplayName("민원인 음성은 지연 없이 바로 STT로 가고, 받은 시각·순번·화자가 붙는다")
		void forwardsCitizenAudioToSttImmediately() {
			long receivedAt = clock.millis();
			coordinator.relayAudio(citizen, frame(1));
			elapse(20);
			coordinator.relayAudio(citizen, frame(2));
			coordinator.relayAudio(agent, frame(3));

			assertThat(agent.audio).isEmpty();
			assertThat(stt.chunks("call-1")).satisfiesExactly(
					first -> {
						assertThat(first.pcm()).isEqualTo(frame(1));
						assertThat(first.sequence()).isZero();
						assertThat(first.receivedAtEpochMs()).isEqualTo(receivedAt);
						assertThat(first.speaker()).isEqualTo(Role.CITIZEN);
					},
					second -> {
						assertThat(second.sequence()).isEqualTo(1);
						assertThat(second.receivedAtEpochMs()).isEqualTo(receivedAt + 20);
					});
		}

		@Test
		@DisplayName("STT 조각의 받은 시각으로 그 구간을 차단할 수 있다")
		void muteBySttTimestamp() {
			coordinator.relayAudio(citizen, frame(1));
			elapse(20);
			coordinator.relayAudio(citizen, frame(2));
			SttChunk risky = stt.chunks("call-1").get(1);

			coordinator.mute("call-1", risky.receivedAtEpochMs(), risky.receivedAtEpochMs());
			elapse(DELAY_MS);

			assertThat(agent.audio).containsExactly(frame(1), new byte[640]);
		}

		@Test
		@DisplayName("통화가 끝나면 STT 스트림을 닫고 이후 음성은 보내지 않는다")
		void closesSttOnHangup() {
			coordinator.handle(agent, new ClientMessage.Hangup("call-1"));
			coordinator.relayAudio(citizen, frame(1));

			assertThat(stt.isClosed("call-1")).isTrue();
			assertThat(stt.chunks("call-1")).isEmpty();
		}

		@Test
		@DisplayName("현황에 통화 중인 상담원·STT 전달량·차단 수가 보인다")
		void snapshotInCall() {
			coordinator.relayAudio(citizen, frame(1));
			coordinator.mute("call-1", null, null);
			FakeConnection waiting = citizen("call-2");

			CallCenterSnapshot snapshot = coordinator.snapshot();

			assertThat(snapshot.agents()).containsExactly(
					new CallCenterSnapshot.AgentView("agent-01", AgentState.IN_CALL, "call-1"));
			assertThat(snapshot.queue()).containsExactly("call-2");
			assertThat(snapshot.calls()).containsExactly(
					new CallCenterSnapshot.CallView("call-1", "CONNECTED", "agent-01", clock.millis(), 1, 640, 0, 1, false, 1),
					new CallCenterSnapshot.CallView("call-2", "QUEUED", null, null, 0, 0, 0, 0, false, 0));
			assertThat(waiting.messages).isNotEmpty();
		}

		@Test
		@DisplayName("차단 유지 중에는 상담원에게 무음만 가고 STT에는 원본이 가며, 끄면 다시 들린다")
		void holdMute() {
			coordinator.relayAudio(citizen, frame(1));
			assertThat(coordinator.holdMute("call-1", true)).hasValue(1);
			coordinator.relayAudio(citizen, frame(2));
			assertThat(coordinator.snapshot().calls().getFirst().muteHold()).isTrue();

			coordinator.holdMute("call-1", false);
			coordinator.relayAudio(citizen, frame(3));
			elapse(DELAY_MS);

			assertThat(agent.audio).containsExactly(new byte[640], new byte[640], frame(3));
			assertThat(stt.chunks("call-1")).extracting(SttChunk::pcm).containsExactly(frame(1), frame(2), frame(3));
			assertThat(coordinator.snapshot().calls().getFirst().muteHold()).isFalse();
		}

		@Test
		@DisplayName("통화 중이 아닌 callId의 차단 유지는 비어 있다")
		void holdMuteUnknownCall() {
			assertThat(coordinator.holdMute("none", true)).isEmpty();
		}

		@Test
		@DisplayName("차단 유지 중 통화가 끝나고 새 통화가 연결되면 새 통화는 차단 없이 시작한다")
		void holdDoesNotLeakToNextCall() {
			coordinator.holdMute("call-1", true);
			coordinator.handle(citizen, new ClientMessage.Hangup("call-1"));
			FakeConnection next = citizen("call-2");
			coordinator.handle(agent, new ClientMessage.Accept("call-2"));

			assertThat(coordinator.holdMute("call-1", false)).isEmpty();
			assertThat(coordinator.snapshot().calls().getFirst().muteHold()).isFalse();
			coordinator.relayAudio(next, frame(7));
			elapse(DELAY_MS);
			assertThat(agent.audio).containsExactly(frame(7));
		}

		@Test
		@DisplayName("STT 자막은 상담원에게 그대로 보낸다")
		void forwardsTranscriptToAgent() {
			stt.listener("call-1").onTranscript(new SttTranscript("call-1-0", "가로등이 꺼져 있어요", false, null, null));

			assertThat(agent.messages).containsExactly(
					new ServerMessage.Transcript("call-1", "call-1-0", "가로등이 꺼져 있어요", false));
		}

		@Test
		@DisplayName("자막에 욕설이 있으면 아직 안 나간 부분을 차단하고 차단 유지를 켜며, 상담원에게 한 번만 알린다")
		void abuseInTranscriptMutesAndNotifiesOnce() {
			long start = clock.millis();
			coordinator.relayAudio(citizen, frame(1));
			elapse(20);
			coordinator.relayAudio(citizen, frame(2));
			SttListener listener = stt.listener("call-1");

			listener.onTranscript(new SttTranscript("call-1-0", "당신 개새끼", false, start, clock.millis()));
			elapse(20);
			coordinator.relayAudio(citizen, frame(3));
			listener.onTranscript(new SttTranscript("call-1-0", "당신 개새끼야", true, start, clock.millis()));
			elapse(DELAY_MS);

			assertThat(agent.audio).containsExactly(new byte[640], new byte[640], new byte[640]);
			assertThat(agent.messages).filteredOn(ServerMessage.RiskDetected.class::isInstance)
					.singleElement()
					.isEqualTo(new ServerMessage.RiskDetected("call-1", "call-1-0", "당신 개새끼", "ABUSE", "MEDIUM", null, "개새끼", 2));
			assertThat(agent.messages).filteredOn(ServerMessage.Transcript.class::isInstance).hasSize(2);
			assertThat(agent.messages).filteredOn(ServerMessage.MuteHoldChanged.class::isInstance)
					.containsExactly(new ServerMessage.MuteHoldChanged("call-1", true));
		}

		@Test
		@DisplayName("발화 시작 시각을 몰라도 차단 유지를 켜서 아직 안 나간 음성을 막는다")
		void keywordWithoutStartStillHolds() {
			coordinator.relayAudio(citizen, frame(1));

			stt.listener("call-1").onTranscript(new SttTranscript("call-1-0", "시발", false, null, clock.millis()));
			elapse(DELAY_MS);

			assertThat(agent.audio).containsExactly(new byte[640]);
			assertThat(agent.messages).filteredOn(ServerMessage.RiskDetected.class::isInstance)
					.singleElement()
					.satisfies(m -> assertThat(((ServerMessage.RiskDetected) m).mutedFrames()).isEqualTo(1));
		}

		@Test
		@DisplayName("위험 발화 뒤 이어진 말도 상담원이 차단 유지를 풀 때까지 막고, 풀면 다시 들린다")
		void riskHoldsUntilAgentReleases() {
			long start = clock.millis();
			coordinator.relayAudio(citizen, frame(1));
			elapse(20);
			long end = clock.millis();
			coordinator.relayAudio(citizen, frame(2));
			elapse(20);
			coordinator.relayAudio(citizen, frame(3)); // 다음 발화

			SttListener listener = stt.listener("call-1");
			listener.onTranscript(new SttTranscript("call-1-0", "이 병신아", true, start, end));
			elapse(DELAY_MS);
			coordinator.relayAudio(citizen, frame(4)); // 한참 뒤에 한 말도 막힌다
			elapse(DELAY_MS);
			assertThat(coordinator.snapshot().calls().getFirst().muteHold()).isTrue();

			coordinator.holdMute("call-1", false); // 상담원이 해제
			listener.onTranscript(new SttTranscript("call-1-0", "이 병신아 진짜", false, start, null)); // 같은 발화가 이어져도
			coordinator.relayAudio(citizen, frame(5));
			elapse(DELAY_MS);

			assertThat(agent.audio).containsExactly(new byte[640], new byte[640], new byte[640], new byte[640], frame(5));
			assertThat(coordinator.snapshot().calls().getFirst().muteHold()).isFalse();
		}

		@Test
		@DisplayName("위험 발화보다 먼저 받은 정상 발화는 지우지 않는다")
		void keepsSpeechBeforeRisk() {
			coordinator.relayAudio(citizen, frame(1)); // 앞 발화
			elapse(20);
			long start = clock.millis();
			coordinator.relayAudio(citizen, frame(2));

			stt.listener("call-1").onTranscript(new SttTranscript("call-1-1", "시발", false, start, null));
			elapse(DELAY_MS);

			assertThat(agent.audio).containsExactly(frame(1), new byte[640]);
		}

		@Test
		@DisplayName("STT 위험 판단(risk)만으로 차단 유지가 켜지고, 위험 알림 다음에 상태 알림이 한 번 간다")
		void sttRiskAloneHolds() {
			long first = clock.millis();
			coordinator.relayAudio(citizen, frame(1));

			stt.listener("call-1").onRisk(new SttRisk(first, first, "AGENT", "ABUSE", "MEDIUM", "..."));

			assertThat(coordinator.snapshot().calls().getFirst().muteHold()).isTrue();
			assertThat(agent.messages).filteredOn(ServerMessage.MuteHoldChanged.class::isInstance).hasSize(1);
			assertThat(agent.messages).filteredOn(m -> m instanceof ServerMessage.RiskDetected || m instanceof ServerMessage.MuteHoldChanged)
					.extracting(Object::getClass)
					.containsExactly(ServerMessage.RiskDetected.class, ServerMessage.MuteHoldChanged.class);
		}

		@Test
		@DisplayName("차단이 시작되면 민원인에게 안내 음성을 실시간으로 들려주고, 그동안 상담원 음성은 민원인에게 가지 않는다")
		void announcesToCitizenOnHold() {
			citizen.audio.clear();
			stt.listener("call-1").onTranscript(new SttTranscript("call-1-0", "시발", false, clock.millis(), null));
			coordinator.relayAudio(agent, frame(5)); // 안내 중 상담원 음성

			elapse(0);
			assertThat(citizen.audio).containsExactly(frame(8)); // 20ms에 한 프레임씩
			elapse(20);
			coordinator.relayAudio(agent, frame(6)); // 안내가 끝난 뒤

			assertThat(citizen.audio).containsExactly(frame(8), frame(9), frame(6));
		}

		@Test
		@DisplayName("안내가 끝난 뒤 차단 유지 중에 새 위험 발화가 와도 안내를 다시 틀지 않는다")
		void announcesOncePerHold() {
			SttListener listener = stt.listener("call-1");
			listener.onTranscript(new SttTranscript("call-1-0", "시발", false, clock.millis(), null));
			elapse(0);
			elapse(20);
			citizen.audio.clear();

			listener.onTranscript(new SttTranscript("call-1-1", "병신아", false, clock.millis(), null));
			elapse(100);

			assertThat(citizen.audio).isEmpty();
		}

		@Test
		@DisplayName("통화가 끝나면 안내를 멈춘다")
		void announcementStopsOnHangup() {
			citizen.audio.clear();
			stt.listener("call-1").onTranscript(new SttTranscript("call-1-0", "시발", false, clock.millis(), null));
			elapse(0);
			coordinator.handle(citizen, new ClientMessage.Hangup("call-1"));
			elapse(100);

			assertThat(citizen.audio).containsExactly(frame(8));
		}

		@Test
		@DisplayName("상담원이 차단을 풀면 안내를 멈추고 상담원 음성을 바로 보낸다")
		void releaseStopsAnnouncement() {
			citizen.audio.clear();
			stt.listener("call-1").onTranscript(new SttTranscript("call-1-0", "시발", false, clock.millis(), null));
			elapse(0);

			coordinator.holdMute("call-1", false);
			coordinator.relayAudio(agent, frame(6));
			elapse(100);

			assertThat(citizen.audio).containsExactly(frame(8), frame(6));
		}

		@Test
		@DisplayName("상담원이 해제한 뒤 새 위험 발화가 나오면 차단 유지를 다시 켠다")
		void newRiskAfterReleaseHoldsAgain() {
			SttListener listener = stt.listener("call-1");
			listener.onTranscript(new SttTranscript("call-1-0", "시발", true, clock.millis(), clock.millis()));
			coordinator.holdMute("call-1", false);

			listener.onTranscript(new SttTranscript("call-1-1", "야 이 새끼야", true, clock.millis(), clock.millis()));

			assertThat(coordinator.snapshot().calls().getFirst().muteHold()).isTrue();
			assertThat(agent.messages).filteredOn(ServerMessage.MuteHoldChanged.class::isInstance)
					.containsExactly(new ServerMessage.MuteHoldChanged("call-1", true), new ServerMessage.MuteHoldChanged("call-1", true));
		}

		@Test
		@DisplayName("STT 위험 판단(risk)도 그 구간을 지우고 차단 유지를 켠다")
		void riskAndHoldFromStt() {
			long first = clock.millis();
			coordinator.relayAudio(citizen, frame(1));
			elapse(20);
			coordinator.relayAudio(citizen, frame(2));
			SttListener listener = stt.listener("call-1");

			listener.onRisk(new SttRisk(first, first, "AGENT", "ABUSE", "MEDIUM", "..."));
			listener.onHold(true);
			coordinator.relayAudio(citizen, frame(3));
			elapse(DELAY_MS);

			assertThat(agent.audio).containsExactly(new byte[640], new byte[640], new byte[640]);
			assertThat(agent.messages).contains(new ServerMessage.MuteHoldChanged("call-1", true));
			assertThat(agent.messages).filteredOn(ServerMessage.RiskDetected.class::isInstance)
					.singleElement()
					.satisfies(m -> assertThat(((ServerMessage.RiskDetected) m).target()).isEqualTo("AGENT"));
		}

		@Test
		@DisplayName("통화가 끝난 뒤 늦게 온 STT 결과는 무시한다")
		void ignoresLateSttResults() {
			SttListener listener = stt.listener("call-1");
			coordinator.handle(citizen, new ClientMessage.Hangup("call-1"));
			agent.clear();

			listener.onTranscript(new SttTranscript("call-1-0", "개새끼", true, null, null));
			listener.onHold(true);

			assertThat(agent.messages).isEmpty();
		}

		@Test
		@DisplayName("640바이트가 아닌 프레임은 INVALID_FRAME")
		void invalidFrame() {
			assertThatThrownBy(() -> coordinator.relayAudio(citizen, new byte[100]))
					.isInstanceOfSatisfying(ProtocolException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_FRAME));
		}

		@Test
		@DisplayName("상담원이 끊으면 양쪽 call-ended, 상담원은 다시 READY, 민원인 연결은 닫힌다")
		void agentHangsUp() {
			coordinator.handle(agent, new ClientMessage.Hangup("call-1"));

			assertThat(agent.messages).containsExactly(
					new ServerMessage.CallEnded("call-1", CallEndReason.HANGUP_AGENT),
					new ServerMessage.StatusChanged(AgentState.READY, StatusReason.CALL_ENDED));
			assertThat(citizen.messages).containsExactly(new ServerMessage.CallEnded("call-1", CallEndReason.HANGUP_AGENT));
			assertThat(citizen.closed).isTrue();
		}

		@Test
		@DisplayName("통화가 끝나면 음성을 더 전달하지 않고, 상담원은 다음 민원인을 받는다")
		void nextCallAfterHangup() {
			coordinator.handle(citizen, new ClientMessage.Hangup("call-1"));
			coordinator.relayAudio(agent, frame(3));
			assertThat(citizen.audio).isEmpty();

			FakeConnection next = citizen("call-2");
			assertThat(agent.last()).isEqualTo(new ServerMessage.Ringing("call-2", RING_TIMEOUT_MS));
			assertThat(next.last()).isEqualTo(new ServerMessage.AgentRinging("call-2"));
		}

		@Test
		@DisplayName("통화 중 상담원 연결이 끊기면 민원인에게 call-ended(DISCONNECTED)")
		void agentDisconnects() {
			coordinator.disconnected(agent);

			assertThat(citizen.messages).containsExactly(new ServerMessage.CallEnded("call-1", CallEndReason.DISCONNECTED));
			assertThat(citizen.closed).isTrue();
		}

		@Test
		@DisplayName("통화 중 민원인 연결이 끊기면 상담원에게 call-ended(DISCONNECTED) 후 READY")
		void citizenDisconnects() {
			coordinator.disconnected(citizen);

			assertThat(agent.messages).containsExactly(
					new ServerMessage.CallEnded("call-1", CallEndReason.DISCONNECTED),
					new ServerMessage.StatusChanged(AgentState.READY, StatusReason.CALL_ENDED));
		}

		@Test
		@DisplayName("통화 중에는 상태를 바꿀 수 없다 (INVALID_STATE)")
		void statusDuringCall() {
			assertThatThrownBy(() -> coordinator.handle(agent, new ClientMessage.Status(AgentState.AWAY)))
					.isInstanceOfSatisfying(ProtocolException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE));
		}
	}

	@Test
	@DisplayName("벨이 울리는 중 상담원 연결이 끊기면 민원인은 다음 상담원에게 배정된다")
	void agentDisconnectsWhileRinging() {
		FakeConnection first = agent("agent-01");
		FakeConnection second = agent("agent-02");
		FakeConnection citizen = citizen("call-1");

		coordinator.disconnected(first);

		assertThat(second.last()).isEqualTo(new ServerMessage.Ringing("call-1", RING_TIMEOUT_MS));
		assertThat(citizen.last()).isEqualTo(new ServerMessage.AgentRinging("call-1"));
		assertThat(ringTimer.activeCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 agentId로 재접속해 새 벨이 울리면, 이전 벨의 늦은 타이머가 새 벨을 끊지 않는다")
	void staleRingTimerAfterReconnect() {
		FakeConnection before = agent("agent-01");
		FakeConnection citizen = citizen("call-1");
		coordinator.disconnected(before);
		FakeConnection after = agent("agent-01");
		assertThat(after.last()).isEqualTo(new ServerMessage.Ringing("call-1", RING_TIMEOUT_MS));

		ringTimer.runEvenIfCancelled(0);

		assertThat(after.all(ServerMessage.RingCancelled.class)).isEmpty();
		coordinator.handle(after, new ClientMessage.Accept("call-1"));
		assertThat(citizen.last()).isEqualTo(new ServerMessage.Connected("call-1", Role.AGENT, 0));
	}

	@Test
	@DisplayName("큐에서 기다리던 민원인이 끊으면 뒷사람 순번이 당겨진다")
	void queuedCitizenLeaves() {
		FakeConnection first = citizen("call-1");
		FakeConnection second = citizen("call-2");

		coordinator.handle(first, new ClientMessage.Hangup("call-1"));

		assertThat(first.last()).isEqualTo(new ServerMessage.CallEnded("call-1", CallEndReason.HANGUP_CITIZEN));
		assertThat(second.last()).isEqualTo(new ServerMessage.Queued("call-2", 1));
	}

	@Test
	@DisplayName("이미 전화를 건 연결로 register하면 INVALID_STATE")
	void citizenCannotRegister() {
		FakeConnection citizen = citizen("call-1");

		assertThatThrownBy(() -> coordinator.handle(citizen, new ClientMessage.Register("agent-x")))
				.isInstanceOfSatisfying(ProtocolException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE));
	}
}
