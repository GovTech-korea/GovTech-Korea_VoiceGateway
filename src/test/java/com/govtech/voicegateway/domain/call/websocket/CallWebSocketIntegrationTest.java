package com.govtech.voicegateway.domain.call.websocket;

import static com.govtech.voicegateway.support.WsTestClient.AUDIO;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;

import com.govtech.voicegateway.support.WsTestClient;

/**
 * 실제 서버에 WebSocket으로 접속해 규격 v2 통화 흐름 전체를 확인한다. (지연 0으로 흐름만 본다)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "voicegateway.delay-ms=0")
class CallWebSocketIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("상담원 등록 → 민원인 전화 → 벨 → 받기 → 음성 왕복 → 끊기")
	void fullCall() throws Exception {
		WsTestClient agent = WsTestClient.connect(port);
		agent.sendText("{\"type\":\"register\",\"role\":\"AGENT\",\"agentId\":\"it-agent\"," + AUDIO + "}");
		assertThat(agent.nextText()).contains("\"type\":\"registered\"", "\"state\":\"READY\"");

		WsTestClient citizen = WsTestClient.connect(port);
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

		agent.close();
	}

	@Test
	@DisplayName("규격 위반 메시지는 error를 받고, 연결을 닫는 오류면 1008로 닫힌다")
	void protocolError() throws Exception {
		WsTestClient client = WsTestClient.connect(port);
		client.sendText("{\"type\":\"join\",\"role\":\"CITIZEN\",\"callId\":\"bad id\"," + AUDIO + "}");

		assertThat(client.nextText()).contains("\"type\":\"error\"", "\"code\":\"INVALID_MESSAGE\"");
		assertThat(client.awaitClose()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
	}

	private static byte[] filled(int value) {
		byte[] frame = new byte[640];
		Arrays.fill(frame, (byte) value);
		return frame;
	}
}
