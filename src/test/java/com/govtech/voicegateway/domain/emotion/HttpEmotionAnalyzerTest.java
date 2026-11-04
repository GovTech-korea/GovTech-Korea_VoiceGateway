package com.govtech.voicegateway.domain.emotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** STT 서버의 감정 판정 API 대신 띄운 가짜 서버로 요청 형식과 응답 처리를 확인한다 */
class HttpEmotionAnalyzerTest {

	private static final EmotionAnalyzer FALLBACK = request -> CompletableFuture.completedFuture(new EmotionResult(11, false));

	private HttpServer server;
	private final AtomicReference<String> contentType = new AtomicReference<>();
	private final AtomicReference<byte[]> body = new AtomicReference<>();
	private volatile int status = 200;
	private volatile String response = "{\"call_id\":\"call-1\",\"state\":\"경고\",\"arousal_score\":82}";

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/acoustic-arousal", exchange -> {
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			body.set(exchange.getRequestBody().readAllBytes());
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private HttpEmotionAnalyzer analyzer() {
		URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/acoustic-arousal");
		return new HttpEmotionAnalyzer(url, 70, Duration.ofSeconds(2), FALLBACK);
	}

	private static EmotionRequest request() {
		return new EmotionRequest("call-1", new byte[] {1, 2, 3, 4}, "도대체 언제까지", 30_000, 36_000, 2_500);
	}

	@Test
	@DisplayName("발화 PCM과 구간·격앙 누적 시간을 multipart로 보내고 arousal_score를 점수로 쓴다")
	void sendsMultipartAndReadsScore() {
		EmotionResult result = analyzer().analyze(request()).join();

		assertThat(result).isEqualTo(new EmotionResult(82, true));
		assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
		String sent = new String(body.get(), StandardCharsets.ISO_8859_1);
		assertThat(sent).contains(
				"name=\"call_id\"\r\n\r\ncall-1\r\n",
				"name=\"start_time\"\r\n\r\n30000\r\n",
				"name=\"end_time\"\r\n\r\n36000\r\n",
				"name=\"is_human_voice\"\r\n\r\ntrue\r\n",
				"name=\"emotional_arousal_duration\"\r\n\r\n2500\r\n",
				"name=\"sample_rate\"\r\n\r\n16000\r\n",
				"name=\"audio\"; filename=\"call-1.pcm\"",
				"\r\n\r\n\u0001\u0002\u0003\u0004\r\n");
	}

	@Test
	@DisplayName("기준 점수 미만이면 격양이 아니다")
	void belowThreshold() {
		response = "{\"state\":\"안정\",\"arousal_score\":35}";

		assertThat(analyzer().analyze(request()).join()).isEqualTo(new EmotionResult(35, false));
	}

	@Test
	@DisplayName("API가 오류를 돌려주면 목업 판정으로 대신한다")
	void fallsBackOnError() {
		status = 400;
		response = "{\"detail\":\"end_time 은 start_time 보다 커야 합니다\"}";

		assertThat(analyzer().analyze(request()).join()).isEqualTo(new EmotionResult(11, false));
	}

	@Test
	@DisplayName("200이어도 arousal_score가 없거나 JSON이 아니면 목업 판정으로 대신한다")
	void fallsBackOnBadBody() {
		response = "{\"state\":\"안정\"}";
		assertThat(analyzer().analyze(request()).join()).isEqualTo(new EmotionResult(11, false));

		response = "<html>oops</html>";
		assertThat(analyzer().analyze(request()).join()).isEqualTo(new EmotionResult(11, false));
	}

	@Test
	@DisplayName("응답이 제한 시간 안에 오지 않으면 목업 판정으로 대신한다")
	void fallsBackOnTimeout() {
		server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(1_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");

		EmotionResult result = new HttpEmotionAnalyzer(url, 70, Duration.ofMillis(200), FALLBACK).analyze(request()).join();

		assertThat(result).isEqualTo(new EmotionResult(11, false));
	}

	@Test
	@DisplayName("API에 닿지 못하면 목업 판정으로 대신한다")
	void fallsBackWhenUnreachable() {
		server.stop(0);

		assertThat(analyzer().analyze(request()).join()).isEqualTo(new EmotionResult(11, false));
	}

	@Test
	@DisplayName("점수는 0~100으로 자르고 기준 점수 이상이면 격양이다")
	void result() {
		assertThat(EmotionResult.of(130, 70)).isEqualTo(new EmotionResult(100, true));
		assertThat(EmotionResult.of(69, 70)).isEqualTo(new EmotionResult(69, false));
	}
}
