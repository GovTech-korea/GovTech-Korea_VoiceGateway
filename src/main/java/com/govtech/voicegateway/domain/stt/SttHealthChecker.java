package com.govtech.voicegateway.domain.stt;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지금 설정된 STT 서버가 실제로 응답하는지 확인한다. 통합 콘솔에서 주소만 보고는 서버가 살아 있는지 알 수 없어서 둔다.
 * WebSocket 주소(ws·wss)와 같은 호스트의 {@code /health}(streaming-asr1이 모델·장치를 알려 줌)를 부른다.
 * <p>
 * 연결부터 본문까지 전체를 {@link #TIMEOUT}으로 끊고 본문은 {@link #MAX_BODY_BYTES}까지만 읽는다.
 * 본문을 느리게 흘리는 서버가 있어도 요청 스레드를 오래 잡지 않게 하려고.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SttHealthChecker implements AutoCloseable {

	static final Duration TIMEOUT = Duration.ofSeconds(2);
	static final int MAX_BODY_BYTES = 64 * 1024;

	private final SttEndpoint endpoint;
	private final JsonMapper jsonMapper;
	// uvicorn은 h2c 업그레이드를 받지 않으므로 HTTP/1.1로 고정한다 (기본 HTTP/2면 요청마다 STT 서버 로그에 경고)
	private final HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(TIMEOUT)
			.build();

	public SttHealth check() {
		URI ws = endpoint.current().orElse(null);
		if (ws == null) {
			return SttHealth.notConfigured();
		}
		String url = ws.toString();
		HttpRequest request = HttpRequest.newBuilder(healthUri(ws))
				.timeout(TIMEOUT)
				.header("ngrok-skip-browser-warning", "true") // ngrok 무료 주소의 안내 페이지 대신 서버 응답을 받는다
				.GET()
				.build();
		long start = System.nanoTime();
		CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, info -> new LimitedBody());
		try {
			HttpResponse<String> response = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			long latencyMs = (System.nanoTime() - start) / 1_000_000;
			if (response.statusCode() != 200) {
				return new SttHealth(url, true, false, latencyMs, null, null, "HTTP " + response.statusCode());
			}
			JsonNode body = readJson(response.body());
			return new SttHealth(url, true, true, latencyMs, text(body, "model"), text(body, "device"), null);
		} catch (TimeoutException e) {
			future.cancel(true); // 진행 중인 요청을 끊는다
			return unreachable(url, "2초 안에 응답 없음");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			log.debug("STT 서버 상태 확인 실패: {}", url, cause);
			if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException) {
				return unreachable(url, "2초 안에 응답 없음");
			}
			if (cause instanceof ConnectException) {
				return unreachable(url, "연결 안 됨 (서버가 꺼져 있거나 주소가 틀림)");
			}
			return unreachable(url, "응답 오류 (" + cause.getClass().getSimpleName() + ")");
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			return unreachable(url, "확인 중단");
		}
	}

	@Override
	public void close() {
		client.close();
	}

	/** ws → http, wss → https, 경로는 /health */
	static URI healthUri(URI ws) {
		String scheme = "wss".equals(ws.getScheme()) ? "https" : "http";
		try {
			return new URI(scheme, null, ws.getHost(), ws.getPort(), "/health", null, null);
		} catch (URISyntaxException e) {
			throw new IllegalStateException("STT 주소에서 /health 주소를 만들 수 없습니다: " + ws, e);
		}
	}

	private static SttHealth unreachable(String url, String error) {
		return new SttHealth(url, true, false, null, null, null, error);
	}

	/** /health 본문이 JSON이 아니어도 응답한 것으로 본다 (모델·장치만 비움) */
	private JsonNode readJson(String body) {
		try {
			return jsonMapper.readTree(body);
		} catch (JacksonException e) {
			return jsonMapper.createObjectNode();
		}
	}

	private static String text(JsonNode body, String field) {
		JsonNode value = body.path(field);
		return value.isString() ? value.asString() : null;
	}

	/** 본문을 {@link #MAX_BODY_BYTES}까지만 모으고, 넘치면 받기를 멈춘다 */
	static final class LimitedBody implements HttpResponse.BodySubscriber<String> {

		private final CompletableFuture<String> result = new CompletableFuture<>();
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private Flow.Subscription subscription;

		@Override
		public CompletionStage<String> getBody() {
			return result;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			for (ByteBuffer item : items) {
				int take = Math.min(item.remaining(), MAX_BODY_BYTES - buffer.size());
				byte[] bytes = new byte[take];
				item.get(bytes);
				buffer.write(bytes, 0, take);
				if (buffer.size() >= MAX_BODY_BYTES) {
					subscription.cancel();
					onComplete();
					return;
				}
			}
		}

		@Override
		public void onError(Throwable throwable) {
			result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			result.complete(buffer.toString(StandardCharsets.UTF_8));
		}
	}
}
