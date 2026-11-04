package com.govtech.voicegateway.domain.emotion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 감정 모델 서버 연동. 발화 음성을 WAV(16kHz mono)로 POST하고 {@code {"score": 0~100}}을 받는다.
 * 모델 서버의 입출력이 확정되면 이 형식을 맞춘다.
 */
public class HttpEmotionAnalyzer implements EmotionAnalyzer {

	private static final int SAMPLE_RATE = 16_000;

	private final URI url;
	private final int agitatedScore;
	private final Duration timeout;
	private final HttpClient client;
	private final JsonMapper json = JsonMapper.builder().build();

	public HttpEmotionAnalyzer(URI url, int agitatedScore, Duration timeout) {
		this.url = url;
		this.agitatedScore = agitatedScore;
		this.timeout = timeout;
		this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	@Override
	public CompletableFuture<EmotionResult> analyze(byte[] pcm, String text) {
		HttpRequest request = HttpRequest.newBuilder(url)
				.timeout(timeout)
				.header("Content-Type", "audio/wav")
				.POST(HttpRequest.BodyPublishers.ofByteArray(wav(pcm)))
				.build();
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new IllegalStateException("감정 모델 응답 " + response.statusCode());
			}
			JsonNode score = json.readTree(response.body()).path("score");
			if (!score.isNumber()) {
				throw new IllegalStateException("감정 모델 응답에 score가 없습니다");
			}
			return EmotionResult.of(score.asInt(), agitatedScore);
		});
	}

	static byte[] wav(byte[] pcm) {
		ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes())
				.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
				.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16)
				.put("data".getBytes()).putInt(pcm.length);
		byte[] wav = new byte[44 + pcm.length];
		System.arraycopy(header.array(), 0, wav, 0, 44);
		System.arraycopy(pcm, 0, wav, 44, pcm.length);
		return wav;
	}
}
