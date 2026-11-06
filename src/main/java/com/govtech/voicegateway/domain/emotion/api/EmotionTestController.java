package com.govtech.voicegateway.domain.emotion.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.govtech.voicegateway.domain.emotion.EmotionAnalyzer;
import com.govtech.voicegateway.domain.emotion.EmotionProperties;
import com.govtech.voicegateway.domain.emotion.EmotionRequest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 테스트 콘솔의 '감정 테스트': 음원 파일의 발화 구간 하나를 통화와 같은 감정 판단(STT 서버 감정 API, 실패하면 목업)에 넣어 본다.
 * 테스트 콘솔을 끄면 함께 꺼진다.
 */
@RestController
@ConditionalOnBooleanProperty(name = "voicegateway.test-console.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class EmotionTestController {

	/** 콘솔에서 보낸 구간이라는 표시 (STT 서버 로그에서 통화와 구분) */
	static final String CALL_ID = "console-emotion-test";
	/** 발화 하나로 받는 최대 길이: 16kHz PCM16 60초 */
	static final int MAX_BYTES = 16_000 * 2 * 60;
	/** 파일 안 시각 상한 (24시간). 넘으면 끝 시각 계산이 넘칠 수 있다 */
	static final long MAX_MS = 24L * 60 * 60 * 1000;

	private final EmotionAnalyzer emotionAnalyzer;
	private final EmotionProperties emotionProperties;

	/**
	 * @param request           본문: 발화 구간 음성 (PCM16 little-endian, 16kHz, 모노)
	 * @param startMs           파일 처음부터 잰 구간 시작 (ms)
	 * @param arousalDurationMs 이 구간 전까지 격양이 이어진 시간 (ms). 통화에서는 게이트웨이가 센다
	 * @param text              발화 문장 (목업 판단에 쓴다, 없어도 됨)
	 */
	@PostMapping(path = "/api/v1/emotion/analyze", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public CompletableFuture<EmotionTestResponse> analyze(HttpServletRequest request,
			@RequestParam(defaultValue = "0") long startMs,
			@RequestParam(defaultValue = "0") long arousalDurationMs,
			@RequestParam(required = false) String text) throws IOException {
		if (startMs < 0 || startMs > MAX_MS || arousalDurationMs < 0 || arousalDurationMs > MAX_MS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startMs, arousalDurationMs는 0 이상 24시간 이하여야 합니다");
		}
		byte[] pcm = readLimited(request);
		if (pcm.length == 0 || pcm.length % 2 != 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"음성은 PCM16 16kHz 모노여야 합니다 (받은 크기: " + pcm.length + "바이트)");
		}
		long endMs = startMs + pcm.length / 32; // 16kHz PCM16: 1ms = 32바이트
		return emotionAnalyzer.analyze(new EmotionRequest(CALL_ID, pcm, text == null ? "" : text, startMs, endMs, arousalDurationMs))
				.thenApply(result -> EmotionTestResponse.of(result, startMs, endMs, emotionProperties));
	}

	/** 공개 서버에서 큰 본문으로 메모리를 다 쓰지 않게, 상한까지만 읽고 넘으면 거절한다 */
	private static byte[] readLimited(HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > MAX_BYTES) {
			throw tooLarge();
		}
		try (InputStream in = request.getInputStream()) {
			byte[] pcm = in.readNBytes(MAX_BYTES + 1);
			if (pcm.length > MAX_BYTES) {
				throw tooLarge();
			}
			return pcm;
		}
	}

	private static ResponseStatusException tooLarge() {
		return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "발화 구간은 60초(" + MAX_BYTES + "바이트)까지 받습니다");
	}
}
