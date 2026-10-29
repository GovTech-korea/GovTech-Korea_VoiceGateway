package com.govtech.voicegateway.domain.stt.streaming;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.govtech.voicegateway.domain.stt.SttChunk;
import com.govtech.voicegateway.domain.stt.SttListener;
import com.govtech.voicegateway.domain.stt.SttRisk;
import com.govtech.voicegateway.domain.stt.SttStream;
import com.govtech.voicegateway.domain.stt.SttTranscript;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 통화 하나의 STT 서버 연결.
 * <p>
 * 보내기는 전용 가상 스레드 하나에서 차례로 한다 (음성 수신 스레드를 막지 않고 순서를 지킨다).
 * 접속이 끝나기 전에 들어온 음성은 최대 {@link #MAX_WAITING}개(약 5초)까지 모아 두었다가 보낸다.
 * <p>
 * 서버가 알려 주는 발화 시작 시각(초)은 <b>서버가 실제로 받은 음성</b> 기준이므로,
 * 서버로 넘긴 프레임마다 게이트웨이가 받은 시각을 기록해 두고 그 순서로 바꾼다 (버린 음성이 있어도 어긋나지 않게).
 * STT 쪽이 위험 판단을 보내면({@code risk}, {@code hold}) 그대로 리스너에 넘긴다 (docs/stt-integration.md 6.3).
 * <p>
 * 접속이 끝나기 전에 통화가 끝나거나 시간이 지나면, 나중에 열린 연결은 받자마자 닫는다 (STT 서버에 연결이 남지 않게).
 */
@Slf4j
final class StreamingAsrStream implements SttStream {

	/** 20ms 조각 5개 = 100ms씩 묶어 보낸다 (서버 권장 단위) */
	static final int FRAMES_PER_MESSAGE = 5;
	static final int MAX_WAITING = 50;
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final long FRAME_MS = 20;

	private final String callId;
	private final URI uri;
	private final SttListener listener;
	private final JsonMapper jsonMapper;
	private final ExecutorService sender;
	private final Duration connectTimeout;
	/** 서버로 넘긴 프레임 순서 → 게이트웨이가 받은 시각 */
	private final Timeline timeline = new Timeline();

	// 통화 종료·접속 실패는 다른 스레드(통화 종료, 접속 콜백, 수신)에서도 보므로 volatile
	private volatile boolean closed;
	private volatile boolean failed;

	// 아래는 sender 스레드에서만 쓴다
	private final ByteArrayOutputStream batch = new ByteArrayOutputStream();
	private final long[] batchReceivedAt = new long[FRAMES_PER_MESSAGE];
	private int batched;
	private final Deque<byte[]> waiting = new ArrayDeque<>();
	private WebSocketSession session;
	private boolean finished;
	private long dropped;

	// 아래는 수신 스레드에서만 쓴다 (한 연결의 수신 콜백은 차례로 불린다)
	private final Map<Integer, Long> utteranceStartFrame = new HashMap<>();

	StreamingAsrStream(String callId, URI uri, SttListener listener, JsonMapper jsonMapper, WebSocketClient client) {
		this(callId, uri, listener, jsonMapper, client, CONNECT_TIMEOUT);
	}

	StreamingAsrStream(String callId, URI uri, SttListener listener, JsonMapper jsonMapper, WebSocketClient client,
			Duration connectTimeout) {
		this.callId = callId;
		this.uri = uri;
		this.listener = listener;
		this.jsonMapper = jsonMapper;
		this.connectTimeout = connectTimeout;
		this.sender = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("stt-ws-" + callId).factory());
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add("ngrok-skip-browser-warning", "1"); // ngrok 무료 주소의 안내 페이지를 건너뛴다
		CompletableFuture<WebSocketSession> handshake = client.execute(new Receiver(), headers, uri);
		handshake.whenComplete(this::handshakeDone);
		CompletableFuture.delayedExecutor(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
				.execute(() -> onSender(this::connectTimedOut));
	}

	@Override
	public void send(SttChunk chunk) {
		byte[] pcm = chunk.pcm();
		long receivedAt = chunk.receivedAtEpochMs();
		onSender(() -> append(pcm, receivedAt));
	}

	@Override
	public void close() {
		closed = true;
		onSender(this::finish);
		sender.shutdown();
	}

	/** sender 스레드에서 실행한다. 통화가 끝나 sender가 닫혔으면 버린다 */
	private void onSender(Runnable task) {
		try {
			sender.execute(task);
		} catch (RejectedExecutionException e) {
			// 통화 종료 뒤 늦게 온 작업
		}
	}

	// ---------- 접속 ----------

	private void handshakeDone(WebSocketSession opened, Throwable error) {
		if (error != null) {
			onSender(() -> fail("연결 실패: " + error));
			return;
		}
		try {
			sender.execute(() -> connected(opened));
		} catch (RejectedExecutionException e) {
			closeSession(opened); // 접속 중에 통화가 끝났다
		}
	}

	private void connected(WebSocketSession opened) {
		// 통화 종료(finish)는 이 작업보다 뒤에 줄을 서므로, 여기서 finished면 정말 종료 뒤에 열린 연결이다
		if (finished || failed) {
			closeSession(opened); // 통화가 끝났거나 시간이 지난 뒤 열린 연결
			return;
		}
		session = opened;
		log.info("STT 서버 연결: {} → {}", callId, uri);
		while (!waiting.isEmpty()) {
			deliver(waiting.pollFirst());
		}
	}

	private void connectTimedOut() {
		if (session == null && !failed && !closed) {
			fail("연결 시간 초과 " + connectTimeout.toMillis() + "ms");
		}
	}

	private void fail(String reason) {
		if (failed) {
			return;
		}
		failed = true;
		waiting.clear();
		log.warn("STT 서버 {}: {} → {}. 이 통화는 STT 없이 진행합니다", reason, callId, uri);
		if (session != null) {
			closeSession(session);
		}
	}

	// ---------- 보내기 (sender 스레드) ----------

	private void append(byte[] pcm, long receivedAt) {
		if (failed || finished) {
			return;
		}
		batch.writeBytes(pcm);
		batchReceivedAt[batched++] = receivedAt;
		if (batched == FRAMES_PER_MESSAGE) {
			flushBatch();
		}
	}

	private void flushBatch() {
		if (batched == 0) {
			return;
		}
		byte[] message = batch.toByteArray();
		long[] receivedAt = Arrays.copyOf(batchReceivedAt, batched);
		batch.reset();
		batched = 0;
		if (session == null) {
			if (waiting.size() >= MAX_WAITING) {
				dropped++;
				return; // 서버로 가지 않으므로 시각도 기록하지 않는다
			}
			waiting.addLast(message);
		} else {
			deliver(message);
		}
		timeline.append(receivedAt);
	}

	private void deliver(byte[] message) {
		try {
			session.sendMessage(new BinaryMessage(message));
		} catch (Exception e) {
			fail("전송 실패: " + e);
		}
	}

	private void finish() {
		if (finished) {
			return;
		}
		finished = true;
		if (dropped > 0) {
			log.warn("STT 서버 연결 전에 쌓인 음성이 넘쳐 {}개(100ms 단위)를 버렸습니다: {}", dropped, callId);
		}
		if (session == null || failed) {
			return; // 아직 열리지 않은 연결은 열리는 즉시 connected()가 닫는다
		}
		flushBatch();
		try {
			session.sendMessage(new TextMessage("{\"type\":\"stop\"}"));
		} catch (Exception e) {
			log.debug("STT 종료 알림 실패: {}", callId, e);
		}
		closeSession(session);
	}

	private void closeSession(WebSocketSession target) {
		if (target.isOpen()) {
			try {
				target.close(CloseStatus.NORMAL);
			} catch (Exception e) {
				log.debug("STT 연결 닫기 실패: {}", callId, e);
			}
		}
	}

	// ---------- 받기 (수신 스레드) ----------

	private final class Receiver extends TextWebSocketHandler {

		@Override
		protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
			try {
				handle(jsonMapper.readTree(message.getPayload()));
			} catch (RuntimeException e) {
				log.warn("STT 서버 메시지를 처리하지 못했습니다: {} {}", callId, e.toString());
			}
		}

		@Override
		public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
			if (!closed && !failed) {
				log.warn("STT 서버 연결이 끊겼습니다: {} ({})", callId, status);
			}
		}
	}

	void handle(JsonNode message) {
		String type = message.path("type").asString("");
		switch (type) {
			case "ready" -> {
				int sampleRate = message.path("sample_rate").asInt(16000);
				if (sampleRate != 16000) {
					log.warn("STT 서버가 {}Hz를 기대합니다. 게이트웨이는 16000Hz로 보냅니다: {}", sampleRate, callId);
				}
			}
			case "speech_start" -> {
				long frame = Math.round(message.path("audio_start").asDouble(0) * 1000 / FRAME_MS);
				utteranceStartFrame.put(message.path("id").asInt(), frame);
			}
			case "partial", "final" -> transcript(message, type.equals("final"));
			case "discard" -> utteranceStartFrame.remove(message.path("id").asInt());
			case "risk" -> risk(message);
			case "hold" -> listener.onHold(message.path("on").asBoolean(false));
			case "error" -> log.warn("STT 서버 오류: {} {}", callId, message.path("message").asString(""));
			default -> log.debug("모르는 STT 메시지 무시: {} {}", callId, type);
		}
	}

	private void transcript(JsonNode message, boolean isFinal) {
		int id = message.path("id").asInt();
		String text = message.path("text").asString("");
		Long startFrame = isFinal ? utteranceStartFrame.remove(id) : utteranceStartFrame.get(id);
		Long from = startFrame == null ? null : timeline.at(startFrame);
		Long to = timeline.latest();
		if (isFinal) {
			// 민원인 발화는 개인정보(주소·전화번호 등)를 담을 수 있어 본문은 DEBUG로만 남긴다
			log.info("STT 문장: {} [{}] {}자", callId, id, text.length());
			log.debug("STT 문장 내용: {} [{}] {}", callId, id, text);
		}
		listener.onTranscript(new SttTranscript(callId + "-" + id, text, isFinal, from, to));
	}

	private void risk(JsonNode message) {
		if (!message.hasNonNull("fromEpochMs") || !message.hasNonNull("toEpochMs")) {
			log.warn("구간이 없는 STT 위험 판단은 무시합니다: {}", callId);
			return;
		}
		listener.onRisk(new SttRisk(message.path("fromEpochMs").asLong(), message.path("toEpochMs").asLong(),
				text(message, "target"), text(message, "riskType"), text(message, "severity"), text(message, "text")));
	}

	private static String text(JsonNode message, String field) {
		JsonNode value = message.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

	/** 서버로 넘긴 프레임 순서 → 게이트웨이가 받은 시각. 보내는 스레드가 쓰고 받는 스레드가 읽는다 */
	/** 테스트용: 서버로 넘긴 마지막 프레임의 시각 */
	Long latestDeliveredAt() {
		return timeline.latest();
	}

	static final class Timeline {

		private long[] receivedAt = new long[3000]; // 1분 분량부터 늘려 간다
		private int count;

		synchronized void append(long[] times) {
			if (count + times.length > receivedAt.length) {
				receivedAt = Arrays.copyOf(receivedAt, Math.max(receivedAt.length * 2, count + times.length));
			}
			System.arraycopy(times, 0, receivedAt, count, times.length);
			count += times.length;
		}

		/** @return 서버 기준 그 프레임을 받은 시각. 아직 넘기지 않았으면 마지막으로 넘긴 프레임의 시각 */
		synchronized Long at(long frame) {
			if (count == 0) {
				return null;
			}
			return receivedAt[(int) Math.min(Math.max(frame, 0), count - 1)];
		}

		synchronized Long latest() {
			return count == 0 ? null : receivedAt[count - 1];
		}
	}
}
