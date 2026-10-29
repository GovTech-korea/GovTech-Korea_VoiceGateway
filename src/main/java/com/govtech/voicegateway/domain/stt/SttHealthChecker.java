package com.govtech.voicegateway.domain.stt;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지금 설정된 STT 서버가 실제로 응답하는지 확인한다. 통합 콘솔에서 주소만 보고는 서버가 살아 있는지 알 수 없어서 둔다.
 * WebSocket 주소(ws·wss)와 같은 호스트의 {@code /health}(streaming-asr1이 모델·장치를 알려 줌)를 부른다.
 */
@Component
public class SttHealthChecker {

	static final Duration TIMEOUT = Duration.ofSeconds(2);

	private final SttEndpoint endpoint;
	private final JsonMapper jsonMapper;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	public SttHealthChecker(SttEndpoint endpoint, JsonMapper jsonMapper) {
		this.endpoint = endpoint;
		this.jsonMapper = jsonMapper;
	}

	public SttHealth check() {
		URI ws = endpoint.current().orElse(null);
		if (ws == null) {
			return SttHealth.notConfigured();
		}
		String url = ws.toString();
		long start = System.nanoTime();
		try {
			HttpRequest request = HttpRequest.newBuilder(healthUri(ws))
					.timeout(TIMEOUT)
					.header("ngrok-skip-browser-warning", "true") // ngrok 무료 주소의 안내 페이지 대신 서버 응답을 받는다
					.GET()
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			long latencyMs = (System.nanoTime() - start) / 1_000_000;
			if (response.statusCode() != 200) {
				return new SttHealth(url, true, false, latencyMs, null, null, "HTTP " + response.statusCode());
			}
			JsonNode body = readJson(response.body());
			return new SttHealth(url, true, true, latencyMs, text(body, "model"), text(body, "device"), null);
		} catch (HttpTimeoutException e) {
			return new SttHealth(url, true, false, null, null, null, "2초 안에 응답 없음");
		} catch (IOException e) {
			return new SttHealth(url, true, false, null, null, null, "연결 안 됨 (서버가 꺼져 있거나 주소가 틀림)");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new SttHealth(url, true, false, null, null, null, "확인 중단");
		}
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
}
