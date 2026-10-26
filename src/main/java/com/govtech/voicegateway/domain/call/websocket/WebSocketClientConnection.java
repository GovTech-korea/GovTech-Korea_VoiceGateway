package com.govtech.voicegateway.domain.call.websocket;

import java.io.IOException;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.govtech.voicegateway.domain.call.ClientConnection;
import com.govtech.voicegateway.domain.call.message.ServerMessage;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebSocket 세션을 {@link ClientConnection}으로 감싼다. 여러 스레드(상대 음성, 타이머)가 동시에 보내도
 * 안전하도록 {@link ConcurrentWebSocketSessionDecorator}를 쓴다.
 */
@Slf4j
class WebSocketClientConnection implements ClientConnection {

	private static final int SEND_TIME_LIMIT_MS = 2_000;
	private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

	private final WebSocketSession session;
	private final JsonMapper jsonMapper;

	WebSocketClientConnection(WebSocketSession session, JsonMapper jsonMapper) {
		this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
		this.jsonMapper = jsonMapper;
	}

	@Override
	public String id() {
		return session.getId();
	}

	@Override
	public void send(ServerMessage message) {
		sendSafely(new TextMessage(jsonMapper.writeValueAsString(message)));
	}

	@Override
	public void sendAudio(byte[] frame) {
		sendSafely(new BinaryMessage(frame));
	}

	@Override
	public void close() {
		closeSafely(CloseStatus.NORMAL);
	}

	@Override
	public void closeForPolicyViolation() {
		closeSafely(CloseStatus.POLICY_VIOLATION);
	}

	private void sendSafely(org.springframework.web.socket.WebSocketMessage<?> message) {
		if (!session.isOpen()) {
			return;
		}
		try {
			session.sendMessage(message);
		} catch (IOException | IllegalStateException e) {
			log.debug("전송 실패 ({}): {}", id(), e.getMessage());
		}
	}

	private void closeSafely(CloseStatus status) {
		try {
			session.close(status);
		} catch (IOException e) {
			log.debug("종료 실패 ({}): {}", id(), e.getMessage());
		}
	}
}
