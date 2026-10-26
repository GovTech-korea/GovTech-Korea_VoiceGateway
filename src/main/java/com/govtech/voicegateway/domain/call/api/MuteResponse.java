package com.govtech.voicegateway.domain.call.api;

/**
 * @param mutedFrames 무음 처리한 프레임 수 (프레임 하나 = 20ms)
 */
public record MuteResponse(String callId, int mutedFrames) {
}
