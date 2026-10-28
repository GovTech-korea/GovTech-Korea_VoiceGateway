package com.govtech.voicegateway.domain.call.api;

/**
 * @param hold        지금 차단 유지 중인지
 * @param mutedFrames 켤 때 새로 무음 처리한 대기 프레임 수 (끌 때는 0)
 */
public record MuteHoldResponse(String callId, boolean hold, int mutedFrames) {
}
