package com.govtech.voicegateway.domain.audio;

/**
 * 민원인에게 들려주는 안내 멘트 모음.
 *
 * @param riskHold    욕설로 차단이 새로 켜질 때 ("욕설이 감지되어 잠시 통화가 멈춥니다. 잠시만 기다려 주세요.")
 * @param abuseNotice 폭언 안내: 안내 전 폭언이 허용 횟수에 닿을 때
 * @param afterNotice 폭언 후 안내: 안내 뒤 폭언이 이어질 때
 * @param sessionEnd  세션 종료: 이 멘트가 끝나면 통화를 끊는다
 */
public record Announcements(Announcement riskHold, Announcement abuseNotice, Announcement afterNotice,
		Announcement sessionEnd) {
}
