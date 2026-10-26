package com.govtech.voicegateway.domain.stt.api;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.govtech.voicegateway.domain.stt.recording.SttRecording;
import com.govtech.voicegateway.domain.stt.recording.SttRecordings;

import lombok.RequiredArgsConstructor;

/**
 * STT로 보낸 음성 기록 목록과 WAV 듣기 (테스트 콘솔용). {@code voicegateway.stt.dump-dir}을 설정했을 때만 기록이 있다.
 */
@RestController
@RequestMapping("/api/v1/stt/recordings")
@ConditionalOnBooleanProperty(name = "voicegateway.test-console.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class SttRecordingController {

	private static final MediaType WAV = MediaType.parseMediaType("audio/wav");

	private final SttRecordings recordings;

	@GetMapping
	public List<SttRecording> list() {
		return recordings.list();
	}

	@GetMapping("/{name}")
	public ResponseEntity<InputStreamResource> wav(@PathVariable String name) {
		SttRecordings.Wav wav = recordings.openWav(name)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기록이 없습니다: " + name));
		return ResponseEntity.ok()
				.contentType(WAV)
				.contentLength(wav.length())
				.body(new InputStreamResource(wav.stream()));
	}
}
