package com.govtech.voicegateway.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 게이트웨이 동작 설정 (application.yaml의 voicegateway.*).
 *
 * @param delayMs       민원인 → 상담원 음성 지연 시간
 * @param ringTimeoutMs 상담원 벨 미응답으로 판단하는 시간
 */
@ConfigurationProperties("voicegateway")
public record VoiceGatewayProperties(
		@DefaultValue("800") long delayMs,
		@DefaultValue("20000") long ringTimeoutMs) {
}
