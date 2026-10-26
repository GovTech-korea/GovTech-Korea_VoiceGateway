package com.govtech.voicegateway.domain.call.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.govtech.voicegateway.domain.call.CallCoordinator;
import com.govtech.voicegateway.domain.stt.SttProperties;
import com.govtech.voicegateway.global.config.VoiceGatewayProperties;

import lombok.RequiredArgsConstructor;

/**
 * 테스트 콘솔이 1초마다 불러 상담원·대기열·통화·STT 전달 현황을 보여 준다.
 */
@RestController
@ConditionalOnBooleanProperty(name = "voicegateway.test-console.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class StatusController {

	private final CallCoordinator coordinator;
	private final VoiceGatewayProperties properties;
	private final SttProperties sttProperties;

	@GetMapping("/api/v1/status")
	public StatusResponse status() {
		return StatusResponse.from(coordinator.snapshot(), properties, sttProperties);
	}
}
