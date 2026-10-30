package com.govtech.voicegateway.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.govtech.voicegateway.domain.audio.Announcement;

/**
 * 위험 발화로 차단할 때 민원인에게 들려줄 안내 음성 ("욕설이 감지되어 잠시 통화가 멈춥니다. 잠시만 기다려 주세요.").
 * 만드는 법은 docs/app-integration.md "안내 음성" 참고.
 */
@Configuration
public class AnnouncementConfig {

	@Bean
	Announcement riskHoldAnnouncement() {
		return Announcement.load(new ClassPathResource("announcements/risk-hold.pcm"));
	}
}
