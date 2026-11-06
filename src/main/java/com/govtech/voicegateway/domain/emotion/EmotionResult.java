package com.govtech.voicegateway.domain.emotion;

import java.util.Set;

/**
 * @param score    0~100. 높을수록 격앙된 상태
 * @param agitated 격양으로 볼지 (감정 API의 상태가 경고·격앙, 목업이면 score가 기준 점수 이상)
 * @param source   누가 판단했는지 (감정 API가 실패해 목업으로 대신했으면 MOCK)
 * @param state    판단한 상태 (감정 API의 안정·경고·격앙·음소거, 목업이면 안정·격양)
 * @param runMs    감정 API가 센 연속 격앙 시간 (ms, 1초 넘게 쉬면 0). 모르면 -1 (목업)
 */
public record EmotionResult(int score, boolean agitated, Source source, String state, long runMs) {

	/** 감정 API가 격양으로 보는 상태: 경고(격앙 음성이 이어짐)와 격앙(쉬지 않고 오래 이어짐) */
	private static final Set<String> AGITATED_STATES = Set.of("경고", "격앙");

	public EmotionResult(int score, boolean agitated) {
		this(score, agitated, Source.API, agitated ? "격양" : "안정", -1);
	}

	public static EmotionResult of(int score, int agitatedScore) {
		return of(score, agitatedScore, Source.API);
	}

	public static EmotionResult of(int score, int agitatedScore, Source source) {
		int clamped = Math.clamp(score, 0, 100);
		boolean agitated = clamped >= agitatedScore;
		return new EmotionResult(clamped, agitated, source, agitated ? "격양" : "안정", -1);
	}

	/**
	 * 감정 API 응답: 격양 여부는 서버가 판정한 상태로 정한다. 다만 서버는 조용한 동안 직전 상태를 유지하므로,
	 * 경고·격앙이어도 연속 격앙 시간이 0이면(1초 넘게 격앙 음성이 없음) 격양으로 보지 않는다. 모르면(-1) 상태만 본다.
	 */
	public static EmotionResult ofState(int score, String state, long runMs) {
		boolean agitated = AGITATED_STATES.contains(state) && runMs != 0;
		return new EmotionResult(Math.clamp(score, 0, 100), agitated, Source.API, state, runMs);
	}

	public enum Source {
		/** STT 서버 감정 API */
		API,
		/** 목업 (목소리 크기·문장) */
		MOCK
	}
}
