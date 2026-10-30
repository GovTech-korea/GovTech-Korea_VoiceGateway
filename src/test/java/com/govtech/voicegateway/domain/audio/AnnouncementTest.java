package com.govtech.voicegateway.domain.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AnnouncementTest {

	@Test
	@DisplayName("PCM을 640바이트 프레임으로 나누고, 남는 끝은 무음으로 채운다")
	void splitsIntoFrames() {
		byte[] pcm = new byte[640 + 100];
		Arrays.fill(pcm, (byte) 1);

		Announcement announcement = Announcement.fromPcm(pcm);

		assertThat(announcement.frames()).hasSize(2);
		assertThat(announcement.frames().get(1)).hasSize(640);
		assertThat(announcement.frames().get(1)[99]).isEqualTo((byte) 1);
		assertThat(announcement.frames().get(1)[100]).isZero();
		assertThat(announcement.durationMs()).isEqualTo(40);
	}

	@Test
	@DisplayName("들어 있는 차단 안내 음성을 읽을 수 있다")
	void bundledRiskHold() {
		Announcement announcement = Announcement.load(new ClassPathResource("announcements/risk-hold.pcm"));

		assertThat(announcement.durationMs()).isBetween(2_000L, 10_000L);
	}
}
