package com.govtech.voicegateway.domain.call.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 실제 서버에 WebSocket으로 접속해 규격 v2 통화 흐름 전체를 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "voicegateway.delay-ms=0")
class CallWebSocketIntegrationTest {

	private static final String AUDIO =
			"\"audio\":{\"encoding\":\"PCM16LE\",\"sampleRate\":16000,\"channels\":1,\"frameMs\":20}";

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("상담원 등록 → 민원인 전화 → 벨 → 받기 → 음성 왕복 → 끊기")
	void fullCall() throws Exception {
		TestClient agent = connect();
		agent.sendText("{\"type\":\"register\",\"role\":\"AGENT\",\"agentId\":\"it-agent\"," + AUDIO + "}");
		assertThat(agent.nextText()).contains("\"type\":\"registered\"", "\"state\":\"READY\"");

		TestClient citizen = connect();
		citizen.sendText("{\"type\":\"join\",\"role\":\"CITIZEN\",\"callId\":\"it-call\"," + AUDIO + "}");
		assertThat(agent.nextText()).contains("\"type\":\"ringing\"", "\"callId\":\"it-call\"");
		assertThat(citizen.nextText()).contains("\"type\":\"agent-ringing\"");

		agent.sendText("{\"type\":\"accept\",\"callId\":\"it-call\"}");
		assertThat(agent.nextText()).contains("\"type\":\"connected\"", "\"peerRole\":\"CITIZEN\"");
		assertThat(citizen.nextText()).contains("\"type\":\"connected\"", "\"peerRole\":\"AGENT\"");

		byte[] citizenVoice = filled(7);
		byte[] agentVoice = filled(9);
		citizen.sendBinary(citizenVoice);
		agent.sendBinary(agentVoice);
		assertThat(agent.nextBinary()).isEqualTo(citizenVoice);
		assertThat(citizen.nextBinary()).isEqualTo(agentVoice);

		citizen.sendText("{\"type\":\"hangup\",\"callId\":\"it-call\"}");
		assertThat(citizen.nextText()).contains("\"type\":\"call-ended\"", "\"reason\":\"HANGUP_CITIZEN\"");
		assertThat(agent.nextText()).contains("\"type\":\"call-ended\"");
		assertThat(agent.nextText()).contains("\"type\":\"status-changed\"", "\"state\":\"READY\"", "\"reason\":\"CALL_ENDED\"");
		assertThat(citizen.awaitClose()).isEqualTo(CloseStatus.NORMAL.getCode());

		agent.session.close();
	}

	@Test
	@DisplayName("규격 위반 메시지는 error를 받고, 연결을 닫는 오류면 1008로 닫힌다")
	void protocolError() throws Exception {
		TestClient client = connect();
		client.sendText("{\"type\":\"join\",\"role\":\"CITIZEN\",\"callId\":\"bad id\"," + AUDIO + "}");

		assertThat(client.nextText()).contains("\"type\":\"error\"", "\"code\":\"INVALID_MESSAGE\"");
		assertThat(client.awaitClose()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
	}

	private TestClient connect() throws Exception {
		TestClient client = new TestClient();
		client.session = new StandardWebSocketClient()
				.execute(client, "ws://localhost:" + port + "/ws/v1/call")
				.get(5, TimeUnit.SECONDS);
		return client;
	}

	private static byte[] filled(int value) {
		byte[] frame = new byte[640];
		Arrays.fill(frame, (byte) value);
		return frame;
	}

	private static final class TestClient extends AbstractWebSocketHandler {

		private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
		private final BlockingQueue<byte[]> binaries = new LinkedBlockingQueue<>();
		private final BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();
		private WebSocketSession session;

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			texts.add(message.getPayload());
		}

		@Override
		protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
			ByteBuffer payload = message.getPayload();
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			binaries.add(bytes);
		}

		@Override
		public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
			closes.add(status.getCode());
		}

		void sendText(String text) throws Exception {
			session.sendMessage(new TextMessage(text));
		}

		void sendBinary(byte[] bytes) throws Exception {
			session.sendMessage(new BinaryMessage(bytes));
		}

		String nextText() throws InterruptedException {
			String text = texts.poll(5, TimeUnit.SECONDS);
			assertThat(text).as("텍스트 메시지 대기").isNotNull();
			return text;
		}

		byte[] nextBinary() throws InterruptedException {
			byte[] bytes = binaries.poll(5, TimeUnit.SECONDS);
			assertThat(bytes).as("음성 프레임 대기").isNotNull();
			return bytes;
		}

		int awaitClose() throws InterruptedException {
			Integer code = closes.poll(5, TimeUnit.SECONDS);
			assertThat(code).as("연결 종료 대기").isNotNull();
			return code;
		}
	}
}
