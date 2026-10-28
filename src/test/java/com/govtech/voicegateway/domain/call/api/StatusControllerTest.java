package com.govtech.voicegateway.domain.call.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.govtech.voicegateway.domain.call.AgentState;
import com.govtech.voicegateway.domain.call.CallCenterSnapshot;
import com.govtech.voicegateway.domain.call.CallCoordinator;
import com.govtech.voicegateway.domain.stt.SttProperties;
import com.govtech.voicegateway.global.config.VoiceGatewayProperties;

@WebMvcTest(StatusController.class)
@Import(StatusControllerTest.Properties.class)
@TestPropertySource(properties = { "voicegateway.delay-ms=500", "voicegateway.stt.format=PCM16_8K" })
class StatusControllerTest {

	/** @WebMvcTest는 설정 record를 등록하지 않아 직접 켠다 */
	@TestConfiguration
	@EnableConfigurationProperties({ VoiceGatewayProperties.class, SttProperties.class })
	static class Properties {
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CallCoordinator coordinator;

	@Test
	@DisplayName("설정과 상담원·대기열·통화 현황을 돌려준다")
	void returnsStatus() throws Exception {
		given(coordinator.snapshot()).willReturn(new CallCenterSnapshot(
				List.of(new CallCenterSnapshot.AgentView("agent-01", AgentState.IN_CALL, "call-1")),
				List.of("call-2"),
				List.of(new CallCenterSnapshot.CallView("call-1", "CONNECTED", "agent-01", 1_000L, 50, 16_000, 0, 3, true, 40))));

		mockMvc.perform(get("/api/v1/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.delayMs").value(500))
				.andExpect(jsonPath("$.sttFormat").value("PCM16_8K"))
				.andExpect(jsonPath("$.sttRecording").value(false))
				.andExpect(jsonPath("$.agents[0].state").value("IN_CALL"))
				.andExpect(jsonPath("$.queue[0]").value("call-2"))
				.andExpect(jsonPath("$.calls[0].sttBytes").value(16_000))
				.andExpect(jsonPath("$.calls[0].mutedFrames").value(3))
				.andExpect(jsonPath("$.calls[0].muteHold").value(true));
	}
}
