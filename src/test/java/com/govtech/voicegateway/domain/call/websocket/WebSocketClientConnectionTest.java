package com.govtech.voicegateway.domain.call.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.govtech.voicegateway.domain.call.message.ServerMessage;

import tools.jackson.databind.json.JsonMapper;

class WebSocketClientConnectionTest {

	private WebSocketSession session;
	private WebSocketClientConnection connection;

	@BeforeEach
	void setUp() {
		session = mock(WebSocketSession.class);
		given(session.getId()).willReturn("s1");
		given(session.isOpen()).willReturn(true);
		connection = new WebSocketClientConnection(session, JsonMapper.builder().build());
	}

	@AfterEach
	void tearDown() {
		connection.dispose();
	}

	@Test
	@DisplayName("넣은 순서대로 보내고, close는 앞의 메시지를 다 보낸 뒤 닫는다")
	void sendsInOrderThenCloses() throws Exception {
		connection.send(new ServerMessage.AgentRinging("c1"));
		connection.sendAudio(new byte[640]);
		connection.close();

		InOrder order = inOrder(session);
		order.verify(session, timeout(1000)).sendMessage(any(TextMessage.class));
		order.verify(session, timeout(1000)).sendMessage(any(BinaryMessage.class));
		order.verify(session, timeout(1000)).close(CloseStatus.NORMAL);
	}

	@Test
	@DisplayName("보내기가 호출한 스레드를 막지 않는다 (느린 상대에게 보내도 바로 돌아온다)")
	void doesNotBlockCaller() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		willAnswer(invocation -> {
			release.await(5, TimeUnit.SECONDS);
			return null;
		}).given(session).sendMessage(any());

		long start = System.nanoTime();
		for (int i = 0; i < 10; i++) {
			connection.sendAudio(new byte[640]);
		}
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		release.countDown();

		assertThat(elapsedMs).isLessThan(500);
	}

	@Test
	@DisplayName("전송이 실패하면 연결을 SESSION_NOT_RELIABLE로 닫는다")
	void closesOnSendFailure() throws Exception {
		willThrow(new IOException("broken pipe")).given(session).sendMessage(any());

		connection.send(new ServerMessage.AgentRinging("c1"));

		verify(session, timeout(1000)).close(CloseStatus.SESSION_NOT_RELIABLE);
	}

	@Test
	@DisplayName("상대가 받지 못해 대기열이 넘치면 연결을 닫고 더 쌓지 않는다")
	void closesWhenQueueOverflows() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		willAnswer(invocation -> {
			release.await(5, TimeUnit.SECONDS);
			return null;
		}).given(session).sendMessage(any());

		for (int i = 0; i <= WebSocketClientConnection.MAX_PENDING + 1; i++) {
			connection.sendAudio(new byte[640]);
		}

		verify(session, timeout(1000)).close(CloseStatus.SESSION_NOT_RELIABLE);
		release.countDown();
	}

	@Test
	@DisplayName("닫기 시작한 뒤에는 새 메시지를 보내지 않는다")
	void ignoresAfterClose() throws Exception {
		connection.close();
		connection.send(new ServerMessage.AgentRinging("c1"));

		verify(session, timeout(1000)).close(CloseStatus.NORMAL);
		verify(session, never()).sendMessage(any());
	}
}
