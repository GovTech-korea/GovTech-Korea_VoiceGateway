package com.govtech.voicegateway.domain.audio;

/**
 * 민원인에게 들려주는 안내 멘트 3종 (보호 정책).
 *
 * @param abuseNotice 폭언 안내: 통화가 연결되면 먼저 나간다
 * @param afterNotice 폭언 후 안내: 안내 전 폭언이 허용 횟수에 닿을 때
 * @param sessionEnd  세션 종료: 안내 후 폭언이 추가 횟수에 닿을 때. 이 멘트가 끝나면 통화를 끊는다
 */
public record Announcements(Announcement abuseNotice, Announcement afterNotice, Announcement sessionEnd) {

	public Announcement get(AnnouncementKind kind) {
		return switch (kind) {
			case ABUSE_NOTICE -> abuseNotice;
			case AFTER_NOTICE -> afterNotice;
			case SESSION_END -> sessionEnd;
		};
	}
}
