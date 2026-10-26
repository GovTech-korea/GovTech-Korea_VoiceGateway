package com.govtech.voicegateway.domain.stt.recording;

/**
 * STT로 보낸 음성 기록 파일 하나.
 *
 * @param name            파일 이름 ({@code <callId>-<시작 epoch ms>.pcm})
 * @param callId          통화 ID
 * @param startedAtEpochMs 기록 시작 시각
 * @param bytes           PCM 바이트 수
 * @param durationMs      길이
 */
public record SttRecording(String name, String callId, long startedAtEpochMs, long bytes, long durationMs) {
}
