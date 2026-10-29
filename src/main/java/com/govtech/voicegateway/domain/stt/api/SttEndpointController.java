package com.govtech.voicegateway.domain.stt.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.govtech.voicegateway.domain.stt.SttEndpoint;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * STT 서버 주소 확인·변경. Colab·ngrok 주소가 바뀌어도 게이트웨이를 다시 켜지 않고 바꾼다 (다음 통화부터 적용).
 * 민원인 음성이 가는 곳이므로 ngrok·trycloudflare·localhost만 받는다 ({@link SttEndpoint}).
 */
@RestController
@RequestMapping("/api/v1/stt/endpoint")
@ConditionalOnBooleanProperty(name = "voicegateway.test-console.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class SttEndpointController {

	private final SttEndpoint endpoint;

	@GetMapping
	public SttEndpointResponse get() {
		return SttEndpointResponse.of(endpoint.current());
	}

	/** 본문 {@code {"url": "https://xxxx.ngrok-free.app"}}. url을 비우면 STT 서버로 보내지 않는다 */
	@PutMapping
	public SttEndpointResponse set(@Valid @RequestBody SttEndpointRequest request) {
		try {
			return SttEndpointResponse.of(endpoint.set(request.url()));
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
		}
	}
}
