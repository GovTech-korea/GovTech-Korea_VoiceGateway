package com.govtech.voicegateway.domain.emotion;

/**
 * @param score    0~100. 높을수록 격앙된 상태
 * @param agitated 격양으로 볼지 (score가 기준 점수 이상)
 */
public record EmotionResult(int score, boolean agitated) {

	public static EmotionResult of(int score, int agitatedScore) {
		int clamped = Math.clamp(score, 0, 100);
		return new EmotionResult(clamped, clamped >= agitatedScore);
	}
}
