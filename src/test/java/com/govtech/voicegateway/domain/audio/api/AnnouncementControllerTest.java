package com.govtech.voicegateway.domain.audio.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import com.govtech.voicegateway.domain.audio.Announcement;
import com.govtech.voicegateway.domain.audio.Announcements;

@WebMvcTest(AnnouncementController.class)
class AnnouncementControllerTest {

	@TestConfiguration
	static class Config {
		@Bean
		Announcements announcements() {
			byte[] frame = new byte[640];
			return new Announcements(new Announcement(List.of(frame)), new Announcement(List.of(frame, frame)),
					new Announcement(List.of(frame)), new Announcement(List.of(frame, frame, frame)));
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("멘트 3종의 제목·문구·길이·미리듣기 주소를 돌려준다")
	void list() throws Exception {
		mockMvc.perform(get("/api/v1/announcements"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].kind").value("ABUSE_NOTICE"))
				.andExpect(jsonPath("$[0].title").value("폭언 안내"))
				.andExpect(jsonPath("$[0].durationMs").value(40))
				.andExpect(jsonPath("$[0].audioUrl").value("/api/v1/announcements/ABUSE_NOTICE/audio"))
				.andExpect(jsonPath("$[2].kind").value("SESSION_END"));
	}

	@Test
	@DisplayName("멘트 음성을 WAV로 돌려준다")
	void audio() throws Exception {
		byte[] wav = mockMvc.perform(get("/api/v1/announcements/SESSION_END/audio"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("audio/wav"))
				.andReturn().getResponse().getContentAsByteArray();

		assertThat(wav).hasSize(44 + 3 * 640);
		assertThat(new String(wav, 0, 4)).isEqualTo("RIFF");
	}

	@Test
	@DisplayName("없는 멘트는 404")
	void unknown() throws Exception {
		mockMvc.perform(get("/api/v1/announcements/RISK_HOLD/audio"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("웹 화면이 브라우저에서 부를 수 있다 (CORS)")
	void cors() throws Exception {
		mockMvc.perform(get("/api/v1/announcements").header("Origin", "https://web.example.com"))
				.andExpect(header().string("Access-Control-Allow-Origin", "*"));
	}
}
