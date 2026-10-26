package com.govtech.voicegateway.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 실제 서버에 접속하는 테스트용 WebSocket 클라이언트. 받은 메시지를 큐에 모아 두고 기다려서 꺼낸다.
 */
public final class WsTestClient extends AbstractWebSocketHandler {

	public static final String AUDIO =
			"\"audio\":{\"encoding\":\"PCM16LE\",\"sampleRate\":16000,\"channels\":1,\"frameMs\":20}";

	private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
	private final BlockingQueue<Received> binaries = new LinkedBlockingQueue<>();
	private final BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();
	private WebSocketSession session;

	public static WsTestClient connect(int port) throws Exception {
		WsTestClient client = new WsTestClient();
		client.session = new StandardWebSocketClient()
				.execute(client, "ws://localhost:" + port + "/ws/v1/call")
				.get(5, TimeUnit.SECONDS);
		return client;
	}

	/** 받은 음성 프레임과 받은 시각 (nanoTime) */
	public record Received(byte[] frame, long atNanos) {
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		texts.add(message.getPayload());
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
		long now = System.nanoTime();
		ByteBuffer payload = message.getPayload();
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		binaries.add(new Received(bytes, now));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		closes.add(status.getCode());
	}

	public void sendText(String text) throws Exception {
		session.sendMessage(new TextMessage(text));
	}

	public void sendBinary(byte[] bytes) throws Exception {
		session.sendMessage(new BinaryMessage(bytes));
	}

	public String nextText() throws InterruptedException {
		String text = texts.poll(5, TimeUnit.SECONDS);
		assertThat(text).as("텍스트 메시지 대기").isNotNull();
		return text;
	}

	public byte[] nextBinary() throws InterruptedException {
		return nextReceived().frame();
	}

	public Received nextReceived() throws InterruptedException {
		Received received = binaries.poll(5, TimeUnit.SECONDS);
		assertThat(received).as("음성 프레임 대기").isNotNull();
		return received;
	}

	public int awaitClose() throws InterruptedException {
		Integer code = closes.poll(5, TimeUnit.SECONDS);
		assertThat(code).as("연결 종료 대기").isNotNull();
		return code;
	}

	public void close() throws Exception {
		session.close();
	}

	/** 상담원 등록 → 민원인 전화 → 받기까지 진행해 통화를 연결한다. */
	public static WsTestClient[] connectCall(int port, String agentId, String callId) throws Exception {
		WsTestClient agent = connect(port);
		agent.sendText("{\"type\":\"register\",\"role\":\"AGENT\",\"agentId\":\"" + agentId + "\"," + AUDIO + "}");
		agent.nextText();
		WsTestClient citizen = connect(port);
		citizen.sendText("{\"type\":\"join\",\"role\":\"CITIZEN\",\"callId\":\"" + callId + "\"," + AUDIO + "}");
		agent.nextText();
		citizen.nextText();
		agent.sendText("{\"type\":\"accept\",\"callId\":\"" + callId + "\"}");
		agent.nextText();
		citizen.nextText();
		return new WsTestClient[] { agent, citizen };
	}
}
