package com.govtech.voicegateway.domain.stt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class SttHealthCheckerTest {

	private HttpServer server;
	private SttHealthChecker checker;

	@AfterEach
	void stop() {
		if (checker != null) {
			checker.close();
		}
		if (server != null) {
			server.stop(0);
		}
	}

	private SttHealthChecker checker(String url) {
		checker = new SttHealthChecker(
				new SttEndpoint(new SttProperties(SttAudioFormat.PCM16_16K, null, url == null ? null : URI.create(url))),
				JsonMapper.builder().build());
		return checker;
	}

	private int serve(HttpHandler handler) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/health", handler);
		server.start();
		return server.getAddress().getPort();
	}

	private int serve(int status, String body) throws IOException {
		return serve(exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
	}

	@Test
	@DisplayName("STT 서버가 /health에 응답하면 모델·장치·응답 시간을 돌려준다")
	void reachable() throws IOException {
		int port = serve(200, "{\"status\":\"ok\",\"model\":\"nemotron.gguf\",\"device\":\"gpu:0\"}");

		SttHealth health = checker("http://127.0.0.1:" + port).check();

		assertThat(health.configured()).isTrue();
		assertThat(health.reachable()).isTrue();
		assertThat(health.model()).isEqualTo("nemotron.gguf");
		assertThat(health.device()).isEqualTo("gpu:0");
		assertThat(health.latencyMs()).isNotNull();
		assertThat(health.url()).isEqualTo("ws://127.0.0.1:" + port + "/ws/transcribe?language=ko-KR");
		assertThat(health.error()).isNull();
	}

	@Test
	@DisplayName("200이 아니면 응답 없음으로 보고 상태 코드를 알려 준다")
	void errorStatus() throws IOException {
		int port = serve(503, "loading");

		SttHealth health = checker("http://127.0.0.1:" + port).check();

		assertThat(health.reachable()).isFalse();
		assertThat(health.error()).isEqualTo("HTTP 503");
	}

	@Test
	@DisplayName("본문이 JSON이 아니어도 200이면 응답한 것으로 본다")
	void notJson() throws IOException {
		int port = serve(200, "ok");

		SttHealth health = checker("http://127.0.0.1:" + port).check();

		assertThat(health.reachable()).isTrue();
		assertThat(health.model()).isNull();
	}

	@Test
	@DisplayName("헤더를 늦게 보내면 2초 남짓에 끊고 응답 없음")
	void slowHeaders() throws IOException {
		int port = serve(exchange -> {
			sleep(4000);
			exchange.close();
		});

		long start = System.nanoTime();
		SttHealth health = checker("http://127.0.0.1:" + port).check();

		assertThat(health.reachable()).isFalse();
		assertThat(health.error()).isEqualTo("2초 안에 응답 없음");
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
	}

	@Test
	@DisplayName("헤더만 보내고 본문을 끝내지 않아도 2초 남짓에 끊는다")
	void slowBody() throws IOException {
		int port = serve(exchange -> {
			exchange.sendResponseHeaders(200, 0); // 길이 없이(청크) 보내기 시작
			exchange.getResponseBody().write("{\"model\":".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			sleep(4000);
			exchange.close();
		});

		long start = System.nanoTime();
		SttHealth health = checker("http://127.0.0.1:" + port).check();

		assertThat(health.reachable()).isFalse();
		assertThat(health.error()).isEqualTo("2초 안에 응답 없음");
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
	}

	@Test
	@DisplayName("본문이 아무리 커도 64KB까지만 읽고 응답한 것으로 본다")
	void largeBody() throws IOException {
		int port = serve(200, "x".repeat(1024 * 1024));

		SttHealth health = checker("http://127.0.0.1:" + port).check();

		assertThat(health.reachable()).isTrue();
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	@DisplayName("서버가 꺼져 있으면 연결 안 됨")
	void down() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort(); // 닫은 뒤에는 아무도 듣지 않는 포트
		}

		SttHealth health = checker("http://127.0.0.1:" + port).check();

		assertThat(health.configured()).isTrue();
		assertThat(health.reachable()).isFalse();
		assertThat(health.error()).contains("연결 안 됨");
	}

	@Test
	@DisplayName("주소가 없으면 확인하지 않는다")
	void notConfigured() {
		SttHealth health = checker(null).check();

		assertThat(health.configured()).isFalse();
		assertThat(health.reachable()).isFalse();
		assertThat(health.url()).isNull();
	}

	@Test
	@DisplayName("ws는 http, wss는 https로 바꾸고 경로는 /health")
	void healthUri() {
		assertThat(SttHealthChecker.healthUri(URI.create("wss://abc.ngrok-free.app/ws/transcribe?language=ko-KR")))
				.hasToString("https://abc.ngrok-free.app/health");
		assertThat(SttHealthChecker.healthUri(URI.create("ws://localhost:8100/ws/transcribe?language=ko-KR")))
				.hasToString("http://localhost:8100/health");
	}
}
