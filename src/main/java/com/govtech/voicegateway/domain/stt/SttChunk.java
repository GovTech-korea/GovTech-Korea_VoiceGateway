package com.govtech.voicegateway.domain.stt;

import com.govtech.voicegateway.domain.call.Role;

/**
 * STT 엔진으로 보내는 음성 조각 하나 (20ms).
 *
 * @param callId            통화 ID
 * @param speaker           말한 사람
 * @param sequence          통화 안에서 0부터 1씩 늘어나는 순번. 빠진 조각을 알아챌 수 있다
 * @param receivedAtEpochMs 게이트웨이가 이 음성을 받은 시각. 위험 구간 차단(POST /api/v1/calls/{callId}/mute)의
 *                          fromEpochMs·toEpochMs는 이 값으로 지정한다
 * @param format            pcm의 형식
 * @param pcm               헤더 없는 PCM16 little-endian 모노
 */
public record SttChunk(String callId, Role speaker, long sequence, long receivedAtEpochMs, SttAudioFormat format,
		byte[] pcm) {
}
