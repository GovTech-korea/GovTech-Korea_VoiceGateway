package com.govtech.voicegateway.domain.call.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.govtech.voicegateway.domain.call.AgentState;
import com.govtech.voicegateway.domain.call.Role;

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
		DISCONNECTED
	}
}
