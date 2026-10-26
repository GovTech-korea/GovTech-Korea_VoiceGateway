package com.govtech.voicegateway.domain.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.govtech.voicegateway.domain.call.message.ClientMessage;
import com.govtech.voicegateway.domain.call.message.ErrorCode;
import com.govtech.voicegateway.domain.call.message.ProtocolException;
import com.govtech.voicegateway.domain.call.message.ServerMessage;
import com.govtech.voicegateway.domain.call.message.ServerMessage.CallEndReason;
import com.govtech.voicegateway.domain.call.message.ServerMessage.RingCancelReason;
import com.govtech.voicegateway.domain.call.message.ServerMessage.StatusReason;
import com.govtech.voicegateway.global.config.VoiceGatewayProperties;

class CallCoordinatorTest {

	private static final long DELAY_MS = 800;
	private static final long RING_TIMEOUT_MS = 20_000;

	private FakeRingTimer ringTimer;
	private CallCoordinator coordinator;

	@BeforeEach
	void setUp() {
		ringTimer = new FakeRingTimer();
		coordinator = new CallCoordinator(new VoiceGatewayProperties(DELAY_MS, RING_TIMEOUT_MS), ringTimer);
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
		@DisplayName("음성 프레임을 서로에게 전달한다")
		void relaysAudio() {
			coordinator.relayAudio(citizen, frame(1));
			coordinator.relayAudio(agent, frame(2));

			assertThat(agent.audio).containsExactly(frame(1));
			assertThat(citizen.audio).containsExactly(frame(2));
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
