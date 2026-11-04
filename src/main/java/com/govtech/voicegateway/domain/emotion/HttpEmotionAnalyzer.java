package com.govtech.voicegateway.domain.emotion;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * STT 서버의 고객 감정 판정 API(streaming-asr {@code POST /v1/acoustic-arousal}) 연동.
 * 발화 구간 PCM을 multipart로 보내고 {@code arousal_score}(0~100)를 받는다.
 * <p>
 * API의 {@code state}(격앙 등)는 고정 기준(누적 5초)이라 참고로 로그에만 남기고, 격앙이 이어진 시간을 폭언 1회로 셀지는
 * 게이트웨이가 웹 정책(agitationSeconds)으로 판단한다. 호출이 실패하면 {@code fallback}으로 대신 판단한다.
 */
@Slf4j
public class HttpEmotionAnalyzer implements EmotionAnalyzer {

	private final URI url;
	private final int agitatedScore;
	private final Duration timeout;
	private final EmotionAnalyzer fallback;
	private final HttpClient client;
	private final JsonMapper json = JsonMapper.builder().build();

	public HttpEmotionAnalyzer(URI url, int agitatedScore, Duration timeout, EmotionAnalyzer fallback) {
		this.url = url;
		this.agitatedScore = agitatedScore;
		this.timeout = timeout;
		this.fallback = fallback;
		this.client = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build();
	}

	@Override
	public CompletableFuture<EmotionResult> analyze(EmotionRequest request) {
		String boundary = "momin-" + UUID.randomUUID();
		HttpRequest http = HttpRequest.newBuilder(url)
				.timeout(timeout)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, request)))
				.build();
		return client.sendAsync(http, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> parse(request, response))
				.exceptionallyCompose(e -> {
					log.warn("감정 판정 API 실패, 목업으로 대신함: {} {}", request.callId(), e.toString());
					return fallback.analyze(request);
				});
	}

	private EmotionResult parse(EmotionRequest request, HttpResponse<String> response) {
		if (response.statusCode() != 200) {
			String body = response.body();
			throw new IllegalStateException("감정 판정 API 응답 " + response.statusCode() + ": "
					+ (body.length() > 200 ? body.substring(0, 200) + "…" : body));
		}
		JsonNode body = json.readTree(response.body());
		JsonNode score = body.path("arousal_score");
		if (!score.isNumber()) {
			throw new IllegalStateException("감정 판정 API 응답에 arousal_score가 없습니다");
		}
		log.info("감정 판정: {} [{}-{}] score={} state={}", request.callId(), request.startMs(), request.endMs(),
				score.asInt(), body.path("state").asString(""));
		return EmotionResult.of(score.asInt(), agitatedScore);
	}

	/** API 규격의 필드: audio(헤더 없는 PCM), call_id, start_time, end_time, is_human_voice, emotional_arousal_duration */
	static byte[] multipart(String boundary, EmotionRequest request) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		field(out, boundary, "call_id", request.callId());
		field(out, boundary, "start_time", Long.toString(request.startMs()));
		field(out, boundary, "end_time", Long.toString(request.endMs()));
		// STT가 문장을 확정한 구간만 보내므로 사람 목소리가 있다
		field(out, boundary, "is_human_voice", "true");
		field(out, boundary, "emotional_arousal_duration", Long.toString(request.arousalDurationMs()));
		field(out, boundary, "sample_rate", "16000");
		field(out, boundary, "bit_depth", "16");
		field(out, boundary, "channels", "1");
		write(out, "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"audio\"; filename=\"" + request.callId() + ".pcm\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n");
		out.writeBytes(request.pcm());
		write(out, "\r\n--" + boundary + "--\r\n");
		return out.toByteArray();
	}

	private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
		write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
	}

	private static void write(ByteArrayOutputStream out, String text) {
		out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
	}
}
