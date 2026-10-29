package com.govtech.voicegateway.domain.stt;

/**
 * STT·위험 탐지가 찾은 위험 구간. 대상·유형·위험도는 MOMIN 보호 판단 모델의 값을 그대로 쓴다.
 *
 * @param fromEpochMs 위험 구간의 첫 조각을 게이트웨이가 받은 시각
 * @param toEpochMs   위험 구간의 마지막 조각을 게이트웨이가 받은 시각
 * @param target      AGENT, SELF, GROUP, THIRD_PARTY, UNKNOWN (모르면 null)
 * @param riskType    NORMAL, ABUSE, SEXUAL, THREAT (모르면 null)
 * @param severity    NONE, LOW, MEDIUM, HIGH, CRITICAL (모르면 null)
 * @param text        근거 발화 (모르면 null)
 */
public record SttRisk(long fromEpochMs, long toEpochMs, String target, String riskType, String severity, String text) {
}
