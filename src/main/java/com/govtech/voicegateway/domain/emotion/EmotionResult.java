package com.govtech.voicegateway.domain.emotion;

/**
 * @param score    0~100. 높을수록 격앙된 상태
 * @param agitated 격양으로 볼지 (score가 기준 점수 이상)
 * @param source   누가 판단했는지 (감정 API가 실패해 목업으로 대신했으면 MOCK)
 */
public record EmotionResult(int score, boolean agitated, Source source) {

	public EmotionResult(int score, boolean agitated) {
		this(score, agitated, Source.API);
	}

	public static EmotionResult of(int score, int agitatedScore) {
		return of(score, agitatedScore, Source.API);
	}

	public static EmotionResult of(int score, int agitatedScore, Source source) {
		int clamped = Math.clamp(score, 0, 100);
		return new EmotionResult(clamped, clamped >= agitatedScore, source);
	}

	public enum Source {
		/** STT 서버 감정 API */
		API,
		/** 목업 (목소리 크기·문장) */
		MOCK
	}
}
