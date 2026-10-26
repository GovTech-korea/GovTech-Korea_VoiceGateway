package com.govtech.voicegateway.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.govtech.voicegateway.domain.call.websocket.CallWebSocketHandler;

import lombok.RequiredArgsConstructor;

/**
 * 메시지 버퍼는 서버 기본값(8KB)을 쓴다. 음성 프레임 640바이트, 텍스트 메시지 수백 바이트라 충분하다.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

	private final CallWebSocketHandler callWebSocketHandler;

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// MVP: 앱·테스트 페이지가 어느 주소에서든 접속할 수 있게 허용한다 (배포 전 도메인으로 좁힌다)
		registry.addHandler(callWebSocketHandler, "/ws/v1/call").setAllowedOriginPatterns("*");
	}
}
