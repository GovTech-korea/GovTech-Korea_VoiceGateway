package com.govtech.voicegateway.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "voicegateway.test-console.enabled=false")
@AutoConfigureMockMvc
class TestConsoleDisabledTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("끄면 콘솔 파일, 현황·STT 기록 API가 모두 404")
	void disabled() throws Exception {
		for (String page : new String[] { "index.html", "agent.html", "citizen.html", "console.html", "console.js" }) {
			mockMvc.perform(get("/test/" + page)).andExpect(status().isNotFound());
		}
		mockMvc.perform(get("/test")).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/status")).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/stt/recordings")).andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/emotion/analyze").contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[640]))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("테스트 콘솔을 꺼도 운영 상담사 웹(/ops)과 그 화면이 쓰는 API는 열려 있다")
	void opsConsoleStaysOn() throws Exception {
		mockMvc.perform(get("/ops")).andExpect(redirectedUrl("/ops/index.html"));
		for (String file : new String[] { "index.html", "ops.js", "ops.css", "img/app-icon.png", "img/favicon.png" }) {
			mockMvc.perform(get("/ops/" + file)).andExpect(status().isOk());
		}
		mockMvc.perform(get("/api/v1/policy")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/announcements")).andExpect(status().isOk());
	}
}
