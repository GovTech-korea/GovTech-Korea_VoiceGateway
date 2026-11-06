package com.govtech.voicegateway.domain.emotion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.govtech.voicegateway.domain.emotion.EmotionAnalyzer;
import com.govtech.voicegateway.domain.emotion.EmotionProperties;
import com.govtech.voicegateway.domain.emotion.EmotionRequest;
import com.govtech.voicegateway.domain.emotion.EmotionResult;

@WebMvcTest(EmotionTestController.class)
class EmotionTestControllerTest {

	@TestConfiguration
	static class Config {
		@Bean
		EmotionProperties emotionProperties() {
			return new EmotionProperties(URI.create("http://127.0.0.1:8100/v1/acoustic-arousal"), 70, 3000);
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EmotionAnalyzer emotionAnalyzer;

	@Test
	@DisplayName("발화 구간 PCM을 통화와 같은 감정 판단에 넣고 점수·격양 여부·구간을 돌려준다")
	void analyze() throws Exception {
		given(emotionAnalyzer.analyze(any())).willReturn(CompletableFuture.completedFuture(new EmotionResult(82, true)));
		byte[] pcm = new byte[32_000]; // 1초

		MvcResult pending = mockMvc.perform(post("/api/v1/emotion/analyze")
						.param("startMs", "2000").param("arousalDurationMs", "3000").param("text", "빨리 해 주세요!")
						.contentType(MediaType.APPLICATION_OCTET_STREAM).content(pcm))
				.andExpect(request().asyncStarted())
				.andReturn();

		mockMvc.perform(asyncDispatch(pending))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.score").value(82))
				.andExpect(jsonPath("$.agitated").value(true))
				.andExpect(jsonPath("$.agitatedScore").value(70))
				.andExpect(jsonPath("$.startMs").value(2000))
				.andExpect(jsonPath("$.endMs").value(3000))
				.andExpect(jsonPath("$.engine").value("API"));
		ArgumentCaptor<EmotionRequest> sent = ArgumentCaptor.forClass(EmotionRequest.class);
		verify(emotionAnalyzer).analyze(sent.capture());
		assertThat(sent.getValue().pcm()).hasSize(32_000);
		assertThat(sent.getValue().arousalDurationMs()).isEqualTo(3000);
		assertThat(sent.getValue().text()).isEqualTo("빨리 해 주세요!");
	}

	@Test
	@DisplayName("감정 API가 실패해 목업이 판단한 구간은 engine이 MOCK")
	void mockEngine() throws Exception {
		given(emotionAnalyzer.analyze(any())).willReturn(
				CompletableFuture.completedFuture(EmotionResult.of(40, 70, EmotionResult.Source.MOCK)));

		MvcResult pending = mockMvc.perform(post("/api/v1/emotion/analyze")
						.contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[640]))
				.andReturn();

		mockMvc.perform(asyncDispatch(pending))
				.andExpect(jsonPath("$.engine").value("MOCK"))
				.andExpect(jsonPath("$.agitated").value(false));
	}

	@Test
	@DisplayName("빈 음성, 홀수 바이트, 범위 밖 시각은 400")
	void invalid() throws Exception {
		for (byte[] pcm : new byte[][] { new byte[0], new byte[3] }) {
			mockMvc.perform(post("/api/v1/emotion/analyze").contentType(MediaType.APPLICATION_OCTET_STREAM).content(pcm))
					.andExpect(status().isBadRequest());
		}
		for (String startMs : new String[] { "-1", String.valueOf(Long.MAX_VALUE) }) {
			mockMvc.perform(post("/api/v1/emotion/analyze").param("startMs", startMs)
							.contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[640]))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(post("/api/v1/emotion/analyze").param("session", "a b/../c")
						.contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[640]))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(emotionAnalyzer);
	}

	@Test
	@DisplayName("시험 id를 주면 감정 판단 단위가 시험마다 달라지고, 응답에 판정 상태가 온다")
	void sessionMakesCallId() throws Exception {
		given(emotionAnalyzer.analyze(any())).willReturn(
				CompletableFuture.completedFuture(EmotionResult.ofState(40, "경고", 500)));

		MvcResult pending = mockMvc.perform(post("/api/v1/emotion/analyze").param("session", "3f2a-01")
						.contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[640]))
				.andReturn();

		mockMvc.perform(asyncDispatch(pending))
				.andExpect(jsonPath("$.state").value("경고"))
				.andExpect(jsonPath("$.agitated").value(true));
		ArgumentCaptor<EmotionRequest> sent = ArgumentCaptor.forClass(EmotionRequest.class);
		verify(emotionAnalyzer).analyze(sent.capture());
		assertThat(sent.getValue().callId()).isEqualTo("console-emotion-test-3f2a-01");
	}

	@Test
	@DisplayName("60초를 넘는 음성은 413, 감정 판단에 보내지 않는다")
	void tooLarge() throws Exception {
		mockMvc.perform(post("/api/v1/emotion/analyze").contentType(MediaType.APPLICATION_OCTET_STREAM)
						.content(new byte[EmotionTestController.MAX_BYTES + 2]))
				.andExpect(status().isPayloadTooLarge());
		verifyNoInteractions(emotionAnalyzer);
	}

	@Test
	@DisplayName("음성이 아닌 형식(JSON 등)은 415")
	void unsupportedType() throws Exception {
		mockMvc.perform(post("/api/v1/emotion/analyze").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnsupportedMediaType());
	}
}
