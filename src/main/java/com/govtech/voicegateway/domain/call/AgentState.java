package com.govtech.voicegateway.domain.call;

/**
 * 상담원 상태. 앱에 알리는 값은 READY·AWAY뿐이고, RINGING·IN_CALL은 게이트웨이 내부 상태다.
 */
public enum AgentState {
	READY,
	AWAY,
	RINGING,
	IN_CALL
}
