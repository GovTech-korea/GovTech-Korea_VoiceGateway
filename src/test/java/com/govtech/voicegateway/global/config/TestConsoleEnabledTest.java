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
	@DisplayName("기본 설정이면 /test가 콘솔로 이동하고, 콘솔 파일과 현황 API가 열린다")
	void enabledByDefault() throws Exception {
		mockMvc.perform(get("/test")).andExpect(redirectedUrl("/test/index.html"));
		mockMvc.perform(get("/test/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<script src=\"console.js\">")));
		mockMvc.perform(get("/test/console.js")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/status")).andExpect(status().isOk());
	}
}
