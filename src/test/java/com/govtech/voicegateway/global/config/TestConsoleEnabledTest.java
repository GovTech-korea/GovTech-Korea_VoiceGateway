package com.govtech.voicegateway.global.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TestConsoleEnabledTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("기본 설정이면 /test가 역할 선택 화면으로 이동하고, 역할별 화면·통합 콘솔·현황 API가 열린다")
	void enabledByDefault() throws Exception {
		mockMvc.perform(get("/test")).andExpect(redirectedUrl("/test/index.html"));
		mockMvc.perform(get("/test/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("href=\"agent.html\"")))
				.andExpect(content().string(containsString("href=\"citizen.html\"")));
		for (String page : new String[] { "agent.html", "citizen.html", "console.html" }) {
			mockMvc.perform(get("/test/" + page))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("<script src=\"console.js\">")));
		}
		mockMvc.perform(get("/test/console.js")).andExpect(status().isOk());
		for (String image : new String[] { "img/wordmark.png", "img/app-icon.png", "img/haetae.png", "img/favicon.png",
				"img/cloud.svg" }) {
			mockMvc.perform(get("/test/" + image)).andExpect(status().isOk());
		}
		mockMvc.perform(get("/api/v1/status")).andExpect(status().isOk());
	}
}
