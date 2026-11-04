package com.govtech.voicegateway.domain.audio.api;

import com.govtech.voicegateway.domain.audio.Announcement;
import com.govtech.voicegateway.domain.audio.AnnouncementKind;

/**
 * @param audioUrl 미리듣기 음성(WAV) 주소
 */
public record AnnouncementResponse(AnnouncementKind kind, String title, String text, String when, long durationMs,
		String audioUrl) {

	public static AnnouncementResponse from(AnnouncementKind kind, Announcement announcement) {
		return new AnnouncementResponse(kind, kind.title(), kind.text(), kind.when(), announcement.durationMs(),
				"/api/v1/announcements/" + kind.name() + "/audio");
	}
}
