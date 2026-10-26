package com.govtech.voicegateway.domain.call.websocket;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import com.govtech.voicegateway.domain.call.ClientConnection;
import com.govtech.voicegateway.domain.call.message.ServerMessage;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebSocket 세션을 {@link ClientConnection}으로 감싼다.
 * <p>
 * 보내기는 호출한 스레드를 막지 않는다. 연결마다 전용 가상 스레드가 대기열에 쌓인 메시지를 넣은 순서대로 보낸다.
 * 그래서 상담 상태 잠금 안에서 보내도 느린 접속자 하나가 다른 통화를 멈추지 않는다.
 * 전송이 실패하거나 대기열이 {@link #MAX_PENDING}을 넘으면(상대가 받지 못하는 상태) 연결을 닫아
 * 정상 종료 경로({@code afterConnectionClosed})로 정리되게 한다.
 */
@Slf4j
class WebSocketClientConnection implements ClientConnection {

	/** 쌓아 둘 수 있는 최대 메시지 수. 음성 프레임(20ms) 기준 약 5초 분량 */
	static final int MAX_PENDING = 250;

	private final WebSocketSession session;
	private final JsonMapper jsonMapper;
	private final ExecutorService sender;
	private final AtomicInteger pending = new AtomicInteger();
	private final AtomicBoolean closing = new AtomicBoolean();

	WebSocketClientConnection(WebSocketSession session, JsonMapper jsonMapper) {
		this.session = session;
		this.jsonMapper = jsonMapper;
		this.sender = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ws-send-" + session.getId()).factory());
	}

	@Override
	public String id() {
		return session.getId();
	}

	@Override
	public void send(ServerMessage message) {
		enqueue(new TextMessage(jsonMapper.writeValueAsString(message)));
	}

	@Override
	public void sendAudio(byte[] frame) {
		enqueue(new BinaryMessage(frame));
	}

	/** 앞서 넣은 메시지를 모두 보낸 뒤 닫는다. */
	@Override
	public void close() {
		closeAfterPending(CloseStatus.NORMAL);
	}

	@Override
	public void closeForPolicyViolation() {
		closeAfterPending(CloseStatus.POLICY_VIOLATION);
	}

	/** 세션이 닫힌 뒤 전송 스레드를 정리한다. */
	void dispose() {
		closing.set(true);
		sender.shutdownNow();
	}

	private void enqueue(WebSocketMessage<?> message) {
		if (closing.get()) {
			return;
		}
		if (pending.incrementAndGet() > MAX_PENDING) {
			pending.decrementAndGet();
			abort("전송 대기열 초과 (" + MAX_PENDING + "개)");
			return;
		}
		submit(() -> {
			try {
				if (session.isOpen()) {
					session.sendMessage(message);
				}
			} catch (Exception e) {
				abort("전송 실패: " + e.getMessage());
			} finally {
				pending.decrementAndGet();
			}
		});
	}

	private void closeAfterPending(CloseStatus status) {
		if (closing.compareAndSet(false, true)) {
			submit(() -> closeQuietly(status));
		}
	}

	/** 받지 못하는 연결은 대기열을 기다리지 않고 바로 닫는다. 닫기가 막힐 수 있어 별도 가상 스레드에서 한다. */
	private void abort(String reason) {
		if (closing.compareAndSet(false, true)) {
			log.warn("연결 {} 닫음: {}", id(), reason);
			Thread.ofVirtual().start(() -> closeQuietly(CloseStatus.SESSION_NOT_RELIABLE));
		}
	}

	private void submit(Runnable task) {
		try {
			sender.execute(task);
		} catch (RejectedExecutionException e) {
			log.debug("이미 정리된 연결 {}", id());
		}
	}

	private void closeQuietly(CloseStatus status) {
		try {
			session.close(status);
		} catch (Exception e) {
			log.debug("종료 실패 ({}): {}", id(), e.getMessage());
		}
	}
}
