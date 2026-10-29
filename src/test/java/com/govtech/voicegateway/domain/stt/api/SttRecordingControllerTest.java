package com.govtech.voicegateway.domain.stt.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.govtech.voicegateway.domain.stt.recording.SttRecording;
import com.govtech.voicegateway.domain.stt.recording.SttRecordings;

@WebMvcTest(SttRecordingController.class)
class SttRecordingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SttRecordings recordings;

	@Test
	@DisplayName("기록 목록을 돌려준다")
	void list() throws Exception {
		given(recordings.list()).willReturn(List.of(new SttRecording("call-1-1000.pcm", "call-1", 1000, 32_000, 1000)));

		mockMvc.perform(get("/api/v1/stt/recordings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("call-1-1000.pcm"))
				.andExpect(jsonPath("$[0].durationMs").value(1000));
	}

	@Test
	@DisplayName("기록을 audio/wav로 길이와 함께 내려준다")
	void wav() throws Exception {
		byte[] body = { 'R', 'I', 'F', 'F' };
		given(recordings.openWav("call-1-1000.pcm"))
				.willReturn(Optional.of(new SttRecordings.Wav(body.length, new ByteArrayInputStream(body))));

		mockMvc.perform(get("/api/v1/stt/recordings/call-1-1000.pcm"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("audio/wav"))
				.andExpect(header().longValue("Content-Length", body.length))
				.andExpect(content().bytes(body));
	}

	@Test
	@DisplayName("없거나 형식에 맞지 않는 이름이면 404")
	void notFound() throws Exception {
		given(recordings.openWav("nope.pcm")).willReturn(Optional.empty());

		mockMvc.perform(get("/api/v1/stt/recordings/nope.pcm"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("지우면 204, 없으면 404, 아직 쓰고 있으면 409")
	void deleteRecording() throws Exception {
		given(recordings.delete("call-1-1000.pcm")).willReturn(SttRecordings.Deletion.DELETED);
		given(recordings.delete("call-2-1000.pcm")).willReturn(SttRecordings.Deletion.NOT_FOUND);
		given(recordings.delete("call-3-1000.pcm")).willReturn(SttRecordings.Deletion.IN_USE);

		mockMvc.perform(delete("/api/v1/stt/recordings/call-1-1000.pcm")).andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/v1/stt/recordings/call-2-1000.pcm")).andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/v1/stt/recordings/call-3-1000.pcm")).andExpect(status().isConflict());
	}
}
