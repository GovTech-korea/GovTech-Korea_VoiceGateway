package com.govtech.voicegateway.domain.call.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.govtech.voicegateway.support.WsTestClient;
import com.govtech.voicegateway.support.WsTestClient.Received;

/**
 * 실제 서버에서 민원인 음성 지연과 REST 차단이 동작하는지 확인한다 (지연 1000ms, 느린 CI에서도 여유를 두려고 길게 잡음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "voicegateway.delay-ms=1000")
class CallAudioDelayIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("민원인 음성은 약 1초 늦게, 상담원 음성은 바로 도착한다")
	void citizenVoiceIsDelayed() throws Exception {
		WsTestClient[] call = WsTestClient.connectCall(port, "delay-agent", "delay-call");
		WsTestClient agent = call[0];
		WsTestClient citizen = call[1];

		long sentAt = System.nanoTime();
		citizen.sendBinary(filled(5));
		agent.sendBinary(filled(6));

		Received toCitizen = citizen.nextReceived();
		Received toAgent = agent.nextReceived();

		assertThat(TimeUnit.NANOSECONDS.toMillis(toCitizen.atNanos() - sentAt)).isLessThan(500);
		assertThat(TimeUnit.NANOSECONDS.toMillis(toAgent.atNanos() - sentAt)).isBetween(990L, 3000L);
		assertThat(toAgent.frame()).isEqualTo(filled(5));

		agent.close();
		citizen.close();
	}

	@Test
	@DisplayName("지연 중에 차단 API를 부르면 상담원에게 무음이 간다")
	void muteThroughApi() throws Exception {
		WsTestClient[] call = WsTestClient.connectCall(port, "mute-agent", "mute-call");
		WsTestClient agent = call[0];
		WsTestClient citizen = call[1];

		// 첫 HTTP 요청은 서블릿 초기화로 느려 지연 시간을 넘길 수 있으니 먼저 한 번 부른다
		assertThat(postMute("no-such-call").statusCode()).isEqualTo(404);

		citizen.sendBinary(filled(5));
		Thread.sleep(50);
		HttpResponse<String> response = postMute("mute-call");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"mutedFrames\":1");
		assertThat(agent.nextBinary()).isEqualTo(new byte[640]);

		agent.close();
		citizen.close();
	}

	private HttpResponse<String> postMute(String callId) throws Exception {
		return HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/calls/" + callId + "/mute"))
						.POST(HttpRequest.BodyPublishers.noBody())
						.build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private static byte[] filled(int value) {
		byte[] frame = new byte[640];
		Arrays.fill(frame, (byte) value);
		return frame;
	}
}
