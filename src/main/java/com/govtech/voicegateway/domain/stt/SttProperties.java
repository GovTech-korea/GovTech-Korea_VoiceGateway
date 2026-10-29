package com.govtech.voicegateway.domain.stt;

import java.net.URI;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * STT 전달 설정 (application.yaml의 voicegateway.stt.*).
 *
 * @param format  STT로 보내는 형식
 * @param dumpDir 지정하면 통화마다 STT로 보낸 바이트를 {@code <callId>-<시작 epoch ms>.pcm} 파일로 남긴다 (테스트·시연용)
 * @param url     STT 서버 WebSocket 주소 (docs/stt-integration.md 6장). 실행 중에 API로 바꿀 수 있다. 없으면 보내지 않는다
 */
@ConfigurationProperties("voicegateway.stt")
public record SttProperties(
		@DefaultValue("PCM16_16K") SttAudioFormat format,
		Path dumpDir,
		URI url) {
}
