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
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * STT 서버의 고객 감정 판정 API(streaming-asr {@code POST /v1/acoustic-arousal}) 연동.
 * 통화 민원인 음성을 연결 때부터 짧은 구간으로 이어서 multipart로 보내고 {@code state}와 {@code arousal_score}(0~100)를 받는다.
 * 서버가 통화(call_id)별로 화자 기준선을 잡고 그 대비 변화로 판정하므로, 같은 통화의 구간을 시간 순서대로 빠짐없이 보내야 한다.
 * <p>
 * 상태가 경고·격앙이면 격양으로 본다. 서버가 센 연속 격앙 시간({@code emotional_arousal_duration})을 돌려주고,
 * 그 시간이 폭언 1회가 될지는 게이트웨이가 웹 정책(agitationSeconds)으로 판단한다.
 * 호출이 실패하면 {@code fallback}으로 대신 판단한다.
 */
@Slf4j
public class HttpEmotionAnalyzer implements EmotionAnalyzer {

	private final URI url;
	private final Duration timeout;
	private final EmotionAnalyzer fallback;
	private final HttpClient client;
	private final JsonMapper json = JsonMapper.builder().build();
	/** 실패가 이어지는 중이다 (0.5초마다 부르므로 실패·복구가 바뀔 때만 로그를 남긴다) */
	private final AtomicBoolean failing = new AtomicBoolean();

	public HttpEmotionAnalyzer(URI url, Duration timeout, EmotionAnalyzer fallback) {
		this.url = url;
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
					if (failing.compareAndSet(false, true)) {
						log.warn("감정 판정 API 실패, 복구될 때까지 목업으로 대신함: {} {}", request.callId(), e.toString());
					}
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
		if (failing.compareAndSet(true, false)) {
			log.info("감정 판정 API 복구: {}", request.callId());
		}
		JsonNode run = body.path("emotional_arousal_duration");
		return EmotionResult.ofState(score.asInt(), body.path("state").asString(""), run.isNumber() ? run.asLong() : -1);
	}

	/** API 규격의 필드: audio(헤더 없는 PCM), call_id, start_time, end_time, is_human_voice, emotional_arousal_duration */
	static byte[] multipart(String boundary, EmotionRequest request) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		field(out, boundary, "call_id", request.callId());
		field(out, boundary, "start_time", Long.toString(request.startMs()));
		field(out, boundary, "end_time", Long.toString(request.endMs()));
		// 사람 목소리인지는 서버가 음량으로 다시 가른다 (false면 서버가 쉼으로 본다)
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
