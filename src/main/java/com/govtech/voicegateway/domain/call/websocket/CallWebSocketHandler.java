package com.govtech.voicegateway.domain.call.websocket;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.govtech.voicegateway.domain.call.CallCoordinator;
import com.govtech.voicegateway.domain.call.ClientConnection;
import com.govtech.voicegateway.domain.call.message.ClientMessageParser;
import com.govtech.voicegateway.domain.call.message.ProtocolException;
import com.govtech.voicegateway.domain.call.message.ServerMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /ws/v1/call} 처리부. 텍스트는 메시지로 해석해 {@link CallCoordinator}에 넘기고,
 * 바이너리는 음성 프레임으로 상대에게 전달한다. 규격 위반은 error 메시지로 알린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallWebSocketHandler extends AbstractWebSocketHandler {

	private final CallCoordinator coordinator;
	private final ClientMessageParser parser;
	private final JsonMapper jsonMapper;
	private final Map<String, WebSocketClientConnection> connections = new ConcurrentHashMap<>();

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		connections.put(session.getId(), new WebSocketClientConnection(session, jsonMapper));
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		ClientConnection connection = connections.get(session.getId());
		try {
			coordinator.handle(connection, parser.parse(message.getPayload()));
		} catch (ProtocolException e) {
			reject(connection, e);
		}
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
		ClientConnection connection = connections.get(session.getId());
		ByteBuffer payload = message.getPayload();
		byte[] frame = new byte[payload.remaining()];
		payload.get(frame);
		try {
			coordinator.relayAudio(connection, frame);
		} catch (ProtocolException e) {
			reject(connection, e);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		WebSocketClientConnection connection = connections.remove(session.getId());
		if (connection != null) {
			coordinator.disconnected(connection);
			connection.dispose();
		}
	}

	private void reject(ClientConnection connection, ProtocolException e) {
		log.debug("규격 위반 ({}): {} {}", connection.id(), e.code(), e.getMessage());
		connection.send(new ServerMessage.Error(e.code(), e.getMessage()));
		if (e.code().closesConnection()) {
			connection.closeForPolicyViolation();
		}
	}
}
