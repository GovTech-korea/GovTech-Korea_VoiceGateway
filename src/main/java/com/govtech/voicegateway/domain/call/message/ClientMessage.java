package com.govtech.voicegateway.domain.call.message;

import com.govtech.voicegateway.domain.call.AgentState;

/**
 * 앱 → 게이트웨이 텍스트 메시지 (docs/app-integration.md 5절).
 */
public sealed interface ClientMessage {

	/** 상담원 대기 등록. */
	record Register(String agentId) implements ClientMessage {
	}

	/** 상담원 상태 변경. READY 또는 AWAY만 허용한다. */
	record Status(AgentState state) implements ClientMessage {
	}

	record Accept(String callId) implements ClientMessage {
	}

	record Reject(String callId) implements ClientMessage {
	}

	/** 민원인 전화 걸기 (큐 진입). */
	record Join(String callId) implements ClientMessage {
	}

	record Hangup(String callId) implements ClientMessage {
	}
}
