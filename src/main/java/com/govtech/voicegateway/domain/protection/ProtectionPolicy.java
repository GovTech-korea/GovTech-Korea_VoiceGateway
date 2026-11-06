package com.govtech.voicegateway.domain.protection;

/**
 * 보호 정책 (웹 정책설정 화면의 값). 통화가 연결될 때의 값을 그 통화가 끝날 때까지 쓴다.
 *
 * @param abuseLimit       안내 전 폭언 허용 횟수. 이 횟수째 폭언에서 '폭언 후 안내' 멘트를 튼다 (IMMEDIATE면 '세션 종료' 멘트 뒤 끊는다)
 * @param afterNotice      안내 후 차단 방식
 * @param afterNoticeLimit 안내 후 폭언이 이 횟수에 닿으면 '세션 종료' 멘트 뒤 통화를 끊는다 (COUNT일 때만 쓴다)
 * @param agitationSeconds 감정 격양이 이 시간만큼 이어지면 폭언 1회로 센다
 */
public record ProtectionPolicy(int abuseLimit, AfterNotice afterNotice, int afterNoticeLimit, int agitationSeconds) {

	public static final ProtectionPolicy DEFAULT = new ProtectionPolicy(5, AfterNotice.COUNT, 2, 5);

	/** 상담원 화면에 보일 안내 후 허용 횟수 (IMMEDIATE는 안내 후 폭언을 세지 않지만 1로 보여 준다) */
	public int effectiveAfterNoticeLimit() {
		return afterNotice == AfterNotice.IMMEDIATE ? 1 : afterNoticeLimit;
	}

	public enum AfterNotice {
		/** 안내 전 폭언이 허용 횟수에 닿으면 '세션 종료' 멘트 뒤 바로 종료 */
		IMMEDIATE,
		/** 안내 후 폭언이 afterNoticeLimit회에 닿으면 종료 */
		COUNT
	}
}
