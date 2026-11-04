package com.govtech.voicegateway.domain.emotion;

/**
 * 감정 판단을 맡길 민원인 발화 하나.
 *
 * @param callId            통화 ID
 * @param pcm               그 발화 구간의 민원인 음성 (PCM16 LE 16kHz mono)
 * @param text              그 발화의 STT 확정 문장
 * @param startMs           구간 시작 (통화 연결부터 잰 ms)
 * @param endMs             구간 끝 (통화 연결부터 잰 ms)
 * @param arousalDurationMs 이 발화 전까지 격앙이 이어진 시간 (ms)
 */
public record EmotionRequest(String callId, byte[] pcm, String text, long startMs, long endMs, long arousalDurationMs) {
}
