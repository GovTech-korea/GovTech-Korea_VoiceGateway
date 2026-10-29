package com.govtech.voicegateway.domain.stt.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import com.govtech.voicegateway.domain.call.Role;
import com.govtech.voicegateway.domain.stt.SttAudioFormat;
import com.govtech.voicegateway.domain.stt.SttChunk;
import com.govtech.voicegateway.domain.stt.SttListener;
import com.govtech.voicegateway.domain.stt.SttRisk;
import com.govtech.voicegateway.domain.stt.SttTranscript;

import tools.jackson.databind.json.JsonMapper;

class StreamingAsrStreamTest {

	private static final URI URI_ = URI.create("wss://stt.example.com/ws/transcribe?language=ko-KR");

	private final JsonMapper json = JsonMapper.builder().build();
	private final List<SttTranscript> transcripts = new ArrayList<>();
	private final List<Object> events = new ArrayList<>();
	private final SttListener listener = new SttListener() {
		@Override
		public void onTranscript(SttTranscript transcript) {
			transcripts.add(transcript);
		}

		@Override
		public void onRisk(SttRisk risk) {
			events.add(risk);
		}

		@Override
		public void onHold(boolean on) {
			events.add(on);
		}
	};
	private WebSocketClient client;
	private WebSocketSession session;
	private CompletableFuture<WebSocketSession> handshake;
	private WebSocketHandler handler;

	@BeforeEach
	void setUp() {
		client = mock(WebSocketClient.class);
		session = mock(WebSocketSession.class);
		given(session.isOpen()).willReturn(true);
		handshake = new CompletableFuture<>();
		given(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class)))
				.willAnswer(inv -> {
					handler = inv.getArgument(0);
					return handshake;
				});
	}

	/** 보내기 스레드가 그 시각의 프레임까지 처리할 때까지 기다린다 */
	private static void awaitDelivered(StreamingAsrStream stream, long receivedAt) throws InterruptedException {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (!Long.valueOf(receivedAt).equals(stream.latestDeliveredAt()) && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		assertThat(stream.latestDeliveredAt()).isEqualTo(receivedAt);
	}

	private static SttChunk chunk(long sequence, long receivedAt) {
		return new SttChunk("call-1", Role.CITIZEN, sequence, receivedAt, SttAudioFormat.PCM16_16K, new byte[640]);
	}

	@Test
	@DisplayName("20ms 조각 5개를 100ms(3200바이트)로 묶어 보내고, 연결 전에 온 음성은 연결된 뒤 순서대로 보낸다")
	void batchesAndFlushesAfterConnect() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);
		for (int i = 0; i < 10; i++) {
			stream.send(chunk(i, 1000 + i * 20L));
		}

		handshake.complete(session);

		ArgumentCaptor<WebSocketMessage<?>> sent = ArgumentCaptor.forClass(WebSocketMessage.class);
		verify(session, timeout(2000).times(2)).sendMessage(sent.capture());
		assertThat(sent.getAllValues()).allSatisfy(m -> {
			assertThat(m).isInstanceOf(BinaryMessage.class);
			assertThat(m.getPayloadLength()).isEqualTo(3200);
		});
	}

	@Test
	@DisplayName("ngrok 안내 페이지를 건너뛰는 헤더를 붙여 접속한다")
	void sendsNgrokHeader() {
		new StreamingAsrStream("call-1", URI_, listener, json, client);

		ArgumentCaptor<WebSocketHttpHeaders> headers = ArgumentCaptor.forClass(WebSocketHttpHeaders.class);
		verify(client).execute(any(WebSocketHandler.class), headers.capture(), any(URI.class));
		assertThat(headers.getValue().getFirst("ngrok-skip-browser-warning")).isEqualTo("1");
	}

	@Test
	@DisplayName("통화가 끝나면 남은 음성을 보내고 stop을 보낸 뒤 닫는다")
	void stopOnClose() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);
		handshake.complete(session);
		stream.send(chunk(0, 1000));

		stream.close();

		ArgumentCaptor<WebSocketMessage<?>> sent = ArgumentCaptor.forClass(WebSocketMessage.class);
		verify(session, timeout(2000).times(2)).sendMessage(sent.capture());
		assertThat(sent.getAllValues().get(0).getPayloadLength()).isEqualTo(640);
		assertThat(sent.getAllValues().get(1)).isEqualTo(new TextMessage("{\"type\":\"stop\"}"));
		verify(session, timeout(2000)).close(any());
	}

	@Test
	@DisplayName("발화 시작 초를 조각 순번으로 바꿔, 자막에 그 조각을 받은 시각을 붙인다")
	void mapsUtteranceTimeToReceivedAt() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);
		for (int i = 0; i < 200; i++) {
			stream.send(chunk(i, 10_000 + i * 20L)); // 4초 분량
		}
		awaitDelivered(stream, 10_000 + 199 * 20L);

		stream.handle(json.readTree("{\"type\":\"speech_start\",\"id\":3,\"audio_start\":2.4}"));
		stream.handle(json.readTree("{\"type\":\"partial\",\"id\":3,\"text\":\"안녕하세\",\"stt_ms\":6.8}"));
		stream.handle(json.readTree("{\"type\":\"final\",\"id\":3,\"text\":\"안녕하세요.\",\"audio_end\":3.1}"));

		assertThat(transcripts).containsExactly(
				new SttTranscript("call-1-3", "안녕하세", false, 10_000 + 120 * 20L, 10_000 + 199 * 20L),
				new SttTranscript("call-1-3", "안녕하세요.", true, 10_000 + 120 * 20L, 10_000 + 199 * 20L));
	}

	@Test
	@DisplayName("speech_start 없이 온 자막은 시작 시각을 모르는 것으로 넘기고, discard·error·모르는 메시지는 무시한다")
	void unknownStartAndIgnored() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);
		for (int i = 0; i < StreamingAsrStream.FRAMES_PER_MESSAGE; i++) {
			stream.send(chunk(i, 1000 + i * 20L));
		}
		awaitDelivered(stream, 1080);

		stream.handle(json.readTree("{\"type\":\"partial\",\"id\":1,\"text\":\"네\"}"));
		stream.handle(json.readTree("{\"type\":\"discard\",\"id\":1}"));
		stream.handle(json.readTree("{\"type\":\"error\",\"message\":\"x\"}"));
		stream.handle(json.readTree("{\"type\":\"something\"}"));

		assertThat(transcripts).containsExactly(new SttTranscript("call-1-1", "네", false, null, 1080L));
	}

	@Test
	@DisplayName("STT가 보낸 위험 판단(risk)과 차단 유지(hold)를 그대로 넘긴다")
	void forwardsRiskAndHold() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);

		stream.handle(json.readTree("{\"type\":\"risk\",\"fromEpochMs\":1000,\"toEpochMs\":1600,\"target\":\"AGENT\","
				+ "\"riskType\":\"ABUSE\",\"severity\":\"MEDIUM\",\"text\":\"...\"}"));
		stream.handle(json.readTree("{\"type\":\"hold\",\"on\":true}"));

		assertThat(events).containsExactly(new SttRisk(1000, 1600, "AGENT", "ABUSE", "MEDIUM", "..."), true);
	}

	@Test
	@DisplayName("접속이 끝나기 전에 통화가 끝나면, 나중에 열린 연결은 바로 닫고 아무것도 보내지 않는다")
	void closesLateConnectionAfterCallEnded() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);
		stream.send(chunk(0, 1000));
		stream.close();
		Thread.sleep(50);

		handshake.complete(session);

		verify(session, timeout(2000)).close(any());
		verify(session, org.mockito.Mockito.never()).sendMessage(any());
	}

	@Test
	@DisplayName("접속 시간이 지난 뒤 열린 연결도 바로 닫는다")
	void closesConnectionAfterTimeout() throws Exception {
		new StreamingAsrStream("call-1", URI_, listener, json, client, java.time.Duration.ofMillis(50));
		Thread.sleep(200);

		handshake.complete(session);

		verify(session, timeout(2000)).close(any());
		verify(session, org.mockito.Mockito.never()).sendMessage(any());
	}

	@Test
	@DisplayName("연결 전에 쌓인 음성이 넘쳐 버려도, 발화 시각은 서버가 실제로 받은 음성 기준으로 맞춘다")
	void timelineSkipsDroppedAudio() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);
		int beforeConnect = (StreamingAsrStream.MAX_WAITING + 10) * StreamingAsrStream.FRAMES_PER_MESSAGE; // 10묶음은 버려짐
		for (int i = 0; i < beforeConnect; i++) {
			stream.send(chunk(i, 10_000 + i * 20L));
		}
		Thread.sleep(100);
		handshake.complete(session);
		for (int i = beforeConnect; i < beforeConnect + 5; i++) {
			stream.send(chunk(i, 10_000 + i * 20L));
		}
		verify(session, timeout(2000).times(StreamingAsrStream.MAX_WAITING + 1)).sendMessage(any());
		awaitDelivered(stream, 10_000 + (beforeConnect + 4) * 20L);

		// 서버 기준 5.0초 = 서버가 받은 250번째 프레임 = 연결 뒤 처음 보낸 프레임
		stream.handle(json.readTree("{\"type\":\"speech_start\",\"id\":0,\"audio_start\":5.0}"));
		stream.handle(json.readTree("{\"type\":\"partial\",\"id\":0,\"text\":\"네\"}"));

		assertThat(transcripts.getLast().fromEpochMs()).isEqualTo(10_000 + beforeConnect * 20L);
	}

	@Test
	@DisplayName("구간이 없는 STT 위험 판단은 무시한다")
	void ignoresRiskWithoutRange() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);

		stream.handle(json.readTree("{\"type\":\"risk\",\"riskType\":\"ABUSE\"}"));

		assertThat(events).isEmpty();
	}

	@Test
	@DisplayName("서버가 보낸 JSON을 받는 쪽 핸들러가 처리한다")
	void handlerParsesText() throws Exception {
		new StreamingAsrStream("call-1", URI_, listener, json, client);

		handler.handleMessage(session, new TextMessage("{\"type\":\"final\",\"id\":0,\"text\":\"네 감사합니다\"}"));

		assertThat(transcripts).extracting(SttTranscript::text).containsExactly("네 감사합니다");
	}

	@Test
	@DisplayName("접속에 실패하면 통화는 계속되고 음성은 버린다")
	void connectFailure() throws Exception {
		StreamingAsrStream stream = new StreamingAsrStream("call-1", URI_, listener, json, client);
		handshake.completeExceptionally(new java.io.IOException("refused"));
		for (int i = 0; i < 10; i++) {
			stream.send(chunk(i, 1000 + i * 20L));
		}
		stream.close();

		Thread.sleep(200);
		verify(session, org.mockito.Mockito.never()).sendMessage(any());
	}
}
