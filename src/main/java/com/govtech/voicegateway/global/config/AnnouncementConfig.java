package com.govtech.voicegateway.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.govtech.voicegateway.domain.audio.Announcement;
import com.govtech.voicegateway.domain.audio.Announcements;

/**
 * 민원인에게 들려줄 안내 멘트. 문구와 만드는 법은 docs/app-integration.md "안내 음성" 참고.
 */
@Configuration
public class AnnouncementConfig {

	@Bean
	Announcements announcements() {
		return new Announcements(load("abuse-notice"), load("after-notice"), load("session-end"));
	}

	private static Announcement load(String name) {
		return Announcement.load(new ClassPathResource("announcements/" + name + ".pcm"));
	}
}
