package com.govtech.voicegateway.domain.stt;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * STT 전달 설정 (application.yaml의 voicegateway.stt.*).
 *
 * @param format  STT로 보내는 형식
 * @param dumpDir 지정하면 통화마다 STT로 보낸 바이트를 {@code <callId>-<시작 epoch ms>.pcm} 파일로 남긴다 (테스트·시연용)
 */
@ConfigurationProperties("voicegateway.stt")
public record SttProperties(
		@DefaultValue("PCM16_16K") SttAudioFormat format,
		Path dumpDir) {
}
