package com.govtech.voicegateway.domain.emotion.api;

import com.govtech.voicegateway.domain.emotion.EmotionProperties;
import com.govtech.voicegateway.domain.emotion.EmotionResult;

/**
 * @param score         0~100. 높을수록 격앙된 상태
 * @param agitated      격양으로 볼지 (감정 API 상태가 경고·격앙, 목업이면 score가 agitatedScore 이상)
 * @param agitatedScore 목업의 격양 기준 점수
 * @param state         판단한 상태 (감정 API: 안정·경고·격앙·음소거)
 * @param startMs       구간 시작 (파일 처음부터 ms)
 * @param endMs         구간 끝
 * @param engine        이 구간을 실제로 판단한 쪽: API(STT 서버 감정 API) 또는 MOCK(주소 설정이 없거나 API가 실패해 목업으로 대신)
 */
public record EmotionTestResponse(int score, boolean agitated, int agitatedScore, String state, long startMs, long endMs,
		EmotionResult.Source engine) {

	public static EmotionTestResponse of(EmotionResult result, long startMs, long endMs, EmotionProperties properties) {
		return new EmotionTestResponse(result.score(), result.agitated(), properties.agitatedScore(), result.state(), startMs, endMs,
				result.source());
	}
}
