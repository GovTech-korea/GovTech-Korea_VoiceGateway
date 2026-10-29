package com.govtech.voicegateway.domain.stt.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.govtech.voicegateway.domain.stt.SttEndpoint;
import com.govtech.voicegateway.domain.stt.SttProperties;

@WebMvcTest(SttEndpointController.class)
@Import(SttEndpointControllerTest.Config.class)
class SttEndpointControllerTest {

	@TestConfiguration
	@EnableConfigurationProperties(SttProperties.class)
	@Import(SttEndpoint.class)
	static class Config {
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("Colab 주소를 넣으면 정리된 WebSocket 주소로 바꾸고, 조회하면 그 주소를 돌려준다")
	void setAndGet() throws Exception {
		mockMvc.perform(put("/api/v1/stt/endpoint").contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\":\"https://abc.ngrok-free.app\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value("wss://abc.ngrok-free.app/ws/transcribe?language=ko-KR"));
		mockMvc.perform(get("/api/v1/stt/endpoint"))
				.andExpect(jsonPath("$.url").value("wss://abc.ngrok-free.app/ws/transcribe?language=ko-KR"));
	}

	@Test
	@DisplayName("비우면 끈다")
	void clear() throws Exception {
		mockMvc.perform(put("/api/v1/stt/endpoint").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").doesNotExist());
	}

	@Test
	@DisplayName("허용하지 않는 호스트, 형식이 틀린 주소, 너무 긴 주소는 400")
	void rejects() throws Exception {
		for (String body : new String[] { "{\"url\":\"https://evil.example.com\"}", "{\"url\":\"ftp://abc\"}",
				"{\"url\":\"https://" + "a".repeat(2100) + ".ngrok-free.app\"}" }) {
			mockMvc.perform(put("/api/v1/stt/endpoint").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest());
		}
	}
}
