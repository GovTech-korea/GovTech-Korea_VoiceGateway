package com.govtech.voicegateway.domain.call.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.govtech.voicegateway.domain.call.AgentState;
import com.govtech.voicegateway.domain.call.Role;
import com.govtech.voicegateway.domain.protection.CallProtection;

/**
 * 게이트웨이 → 앱 텍스트 메시지 (docs/app-integration.md 5절). JSON의 "type" 값은 {@link #type()}이다.
 */
public sealed interface ServerMessage {

	@JsonProperty("type")
	String type();

	record Registered(String agentId, AgentState state) implements ServerMessage {
		@Override
		public String type() {
			return "registered";
		}
	}

	record StatusChanged(AgentState state, StatusReason reason) implements ServerMessage {
		@Override
		public String type() {
			return "status-changed";
		}
	}

	record Ringing(String callId, long timeoutMs) implements ServerMessage {
		@Override
		public String type() {
			return "ringing";
		}
	}

	record RingCancelled(String callId, RingCancelReason reason) implements ServerMessage {
		@Override
		public String type() {
			return "ring-cancelled";
		}
	}

	record Queued(String callId, int position) implements ServerMessage {
		@Override
		public String type() {
			return "queued";
		}
	}

	record AgentRinging(String callId) implements ServerMessage {
		@Override
		public String type() {
			return "agent-ringing";
		}
	}

	record Connected(String callId, Role peerRole, long delayMs) implements ServerMessage {
		@Override
		public String type() {
			return "connected";
		}
	}

	record CallEnded(String callId, CallEndReason reason) implements ServerMessage {
		@Override
		public String type() {
			return "call-ended";
		}
	}

	/** 상담원에게: 민원인 말의 STT 자막 (중간 결과는 같은 utteranceId로 여러 번, 확정은 final=true) */
	record Transcript(String callId, String utteranceId, String text, @JsonProperty("final") boolean isFinal)
			implements ServerMessage {
		@Override
		public String type() {
			return "transcript";
		}
	}

	/** 상담원에게: 위험 발화를 찾아 그 구간을 자동으로 차단함 (발화마다 한 번) */
	record RiskDetected(String callId, String utteranceId, String text, String riskType, String severity, String keyword,
			int mutedFrames) implements ServerMessage {
		@Override
		public String type() {
			return "risk-detected";
		}
	}

	/** 상담원에게: 차단 유지가 켜지거나 꺼짐 (위험 판단이 켜든, 상담원이 REST로 켜고 끄든) */
	record MuteHoldChanged(String callId, boolean hold) implements ServerMessage {
		@Override
		public String type() {
			return "mute-hold-changed";
		}
	}

	/** 상담원에게: 보호 정책 카운터 (통화 연결 시 한 번, 값이 바뀔 때마다) */
	record ProtectionState(String callId, int abuseCount, int abuseLimit, int afterNoticeCount, int afterNoticeLimit,
			int agitationSeconds, int agitationLimitSeconds) implements ServerMessage {
		@Override
		public String type() {
			return "protection-state";
		}
	}

	/** 상담원에게: 민원인 발화 하나의 감정 판단 */
	record Emotion(String callId, int score, boolean agitated) implements ServerMessage {
		@Override
		public String type() {
			return "emotion";
		}
	}

	/** 상담원에게: '음소거 구간 전사' 목록의 한 줄. 같은 eventId면 그 줄을 덮어쓴다 */
	record ProtectionEvent(String callId, String eventId, CallProtection.EventKind kind, long startMs, long endMs,
			String text, String keyword) implements ServerMessage {
		@Override
		public String type() {
			return "protection-event";
		}
	}

	record Error(ErrorCode code, String message) implements ServerMessage {
		@Override
		public String type() {
			return "error";
		}
	}

	enum StatusReason {
		REQUESTED,
		NO_ANSWER,
		CALL_ENDED
	}

	enum RingCancelReason {
		TIMEOUT,
		CITIZEN_LEFT
	}

	enum CallEndReason {
		HANGUP_CITIZEN,
		HANGUP_AGENT,
		DISCONNECTED,
		/** 폭언이 정책 횟수에 닿아 '세션 종료' 멘트 뒤 게이트웨이가 끊음 */
		POLICY_TERMINATED
	}
}
