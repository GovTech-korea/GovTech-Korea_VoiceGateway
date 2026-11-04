package com.govtech.voicegateway.domain.audio;

/**
 * 보호 정책에 따라 민원인에게 나가는 안내 멘트 (정책설정 화면의 '안내 멘트 설정'). 문구는 resources/announcements의 음성과 같다.
 */
public enum AnnouncementKind {

	ABUSE_NOTICE("폭언 안내", "민원 상담 시 폭언이나 욕설은 삼가 주시기 바랍니다. 상담원 보호를 위해 폭언이 계속되면 상담이 종료될 수 있습니다.",
			"통화가 연결되면 바로 (그동안 상담원 음성은 민원인에게 가지 않음)"),
	AFTER_NOTICE("폭언 후 안내", "지금도 폭언이 지속되고 있어 원활한 상담이 어렵습니다. 폭언이 계속되면 상담이 종료됩니다.",
			"안내 전 폭언이 폭언 허용 횟수에 닿을 때"),
	SESSION_END("세션 종료", "폭언이 반복되어 상담을 종료합니다.",
			"폭언 후 안내 뒤 폭언이 추가 폭언 횟수에 닿을 때. 멘트가 끝나면 통화를 끊는다");

	private final String title;
	private final String text;
	private final String when;

	AnnouncementKind(String title, String text, String when) {
		this.title = title;
		this.text = text;
		this.when = when;
	}

	public String title() {
		return title;
	}

	public String text() {
		return text;
	}

	public String when() {
		return when;
	}
}
