package com.govtech.voicegateway.domain.call;

import java.util.List;

/**
 * 한 시점의 상담 현황.
 *
 * @param agents 접속한 상담원 (ID 순)
 * @param queue  대기 중인 통화 ID (배정 순서)
 * @param calls  대기·벨·통화 중인 모든 통화 (ID 순)
 */
public record CallCenterSnapshot(List<AgentView> agents, List<String> queue, List<CallView> calls) {

	/** @param callId 벨이 울리거나 통화 중인 통화, 없으면 null */
	public record AgentView(String agentId, AgentState state, String callId) {
	}

	/**
	 * @param state              QUEUED, RINGING, CONNECTED
	 * @param agentId            벨이 울리거나 통화 중인 상담원, 없으면 null
	 * @param connectedAtEpochMs 통화 연결 시각, 연결 전이면 null
	 * @param sttChunks          STT로 넘긴 조각 수 (20ms, 실패 포함)
	 * @param sttBytes           STT 전달에 성공한 바이트 수
	 * @param sttFailures        STT 전달에 실패한 조각 수
	 * @param mutedFrames        무음 처리한 프레임 수
	 * @param pendingFrames      지연 버퍼에서 상담원에게 나가기를 기다리는 프레임 수
	 */
	public record CallView(String callId, String state, String agentId, Long connectedAtEpochMs, long sttChunks,
			long sttBytes, long sttFailures, long mutedFrames, int pendingFrames) {
	}
}
