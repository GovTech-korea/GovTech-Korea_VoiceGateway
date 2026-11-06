package com.govtech.voicegateway.domain.emotion;

/**
 * 감정 판단을 맡길 민원인 음성 구간 하나 (통화에서는 연결 때부터 이어지는 0.5초 구간).
 *
 * @param callId            감정 API가 화자 기준선을 잡는 단위 (통화에서는 연결마다 고유한 값)
 * @param pcm               그 구간의 민원인 음성 (PCM16 LE 16kHz mono)
 * @param text              그때 자막 (목업 판단에 쓴다)
 * @param startMs           구간 시작 (통화 연결부터 잰 ms)
 * @param endMs             구간 끝 (통화 연결부터 잰 ms)
 * @param arousalDurationMs 직전 판단의 연속 격앙 시간 (ms). 감정 API는 통화 상태를 잃었을 때만 이 값을 이어 받는다
 */
public record EmotionRequest(String callId, byte[] pcm, String text, long startMs, long endMs, long arousalDurationMs) {
}
