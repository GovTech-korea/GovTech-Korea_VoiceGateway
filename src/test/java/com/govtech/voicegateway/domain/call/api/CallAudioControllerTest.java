package com.govtech.voicegateway.domain.call.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.govtech.voicegateway.domain.call.CallCoordinator;

@WebMvcTest(CallAudioController.class)
class CallAudioControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CallCoordinator coordinator;

	@Test
	@DisplayName("본문 없이 부르면 아직 나가지 않은 구간 전체를 차단한다")
	void muteAllPending() throws Exception {
		given(coordinator.mute("call-1", null, null)).willReturn(OptionalInt.of(12));

		mockMvc.perform(post("/api/v1/calls/call-1/mute"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.callId").value("call-1"))
				.andExpect(jsonPath("$.mutedFrames").value(12));
	}

	@Test
	@DisplayName("구간을 주면 그 구간만 차단한다")
	void muteRange() throws Exception {
		given(coordinator.mute("call-1", 1000L, 2000L)).willReturn(OptionalInt.of(3));

		mockMvc.perform(post("/api/v1/calls/call-1/mute")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromEpochMs\":1000,\"toEpochMs\":2000}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mutedFrames").value(3));
	}

	@Test
	@DisplayName("통화 중이 아닌 callId면 404")
	void notInCall() throws Exception {
		given(coordinator.mute(eq("none"), any(), any())).willReturn(OptionalInt.empty());

		mockMvc.perform(post("/api/v1/calls/none/mute"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("시작이 끝보다 크면 400이고 차단하지 않는다")
	void invalidRange() throws Exception {
		mockMvc.perform(post("/api/v1/calls/call-1/mute")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromEpochMs\":2000,\"toEpochMs\":1000}"))
				.andExpect(status().isBadRequest());

		verify(coordinator, never()).mute(any(), any(), any());
	}

	@Test
	@DisplayName("차단 유지 켜기: 켤 때 무음 처리한 대기 프레임 수를 돌려준다")
	void holdOn() throws Exception {
		given(coordinator.holdMute("call-1", true)).willReturn(OptionalInt.of(40));

		mockMvc.perform(put("/api/v1/calls/call-1/mute-hold")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"hold\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hold").value(true))
				.andExpect(jsonPath("$.mutedFrames").value(40));
	}

	@Test
	@DisplayName("차단 유지 끄기")
	void holdOff() throws Exception {
		given(coordinator.holdMute("call-1", false)).willReturn(OptionalInt.of(0));

		mockMvc.perform(put("/api/v1/calls/call-1/mute-hold")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"hold\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hold").value(false));
	}

	@Test
	@DisplayName("차단 유지: 통화 중이 아니면 404, hold가 없으면 400")
	void holdErrors() throws Exception {
		given(coordinator.holdMute(eq("none"), anyBoolean())).willReturn(OptionalInt.empty());

		mockMvc.perform(put("/api/v1/calls/none/mute-hold")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"hold\":true}"))
				.andExpect(status().isNotFound());
		for (String body : new String[] { "{}", "{\"hold\":null}" }) {
			mockMvc.perform(put("/api/v1/calls/call-1/mute-hold")
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(put("/api/v1/calls/call-1/mute-hold"))
				.andExpect(status().isBadRequest());
	}
}
