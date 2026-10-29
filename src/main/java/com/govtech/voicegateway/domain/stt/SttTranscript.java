package com.govtech.voicegateway.domain.stt;

/**
 * STT 인식 결과 (발화 하나에 중간 결과 여러 번, 확정 결과 한 번).
 *
 * @param utteranceId 발화 구분 값. 같은 발화의 중간·확정 결과는 같은 값
 * @param text        인식한 글자
 * @param isFinal     문장이 끝나 확정됐는지 (false면 말하는 도중의 중간 결과)
 * @param fromEpochMs 이 발화의 첫 조각을 게이트웨이가 받은 시각 (모르면 null). 차단 구간의 시작으로 쓴다
 * @param toEpochMs   지금까지 들어간 마지막 조각을 게이트웨이가 받은 시각 (모르면 null)
 */
public record SttTranscript(String utteranceId, String text, boolean isFinal, Long fromEpochMs, Long toEpochMs) {
}
