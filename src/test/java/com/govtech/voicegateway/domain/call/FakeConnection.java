package com.govtech.voicegateway.domain.call;

import java.util.ArrayList;
import java.util.List;

import com.govtech.voicegateway.domain.call.message.ServerMessage;

/** 보낸 메시지와 음성을 기록하는 테스트용 연결. */
class FakeConnection implements ClientConnection {

	private static int sequence;

	private final String id = "conn-" + (++sequence);
	final List<ServerMessage> messages = new ArrayList<>();
	final List<byte[]> audio = new ArrayList<>();
	boolean closed;
	boolean policyClosed;

	@Override
	public String id() {
		return id;
	}

	@Override
	public void send(ServerMessage message) {
		messages.add(message);
	}

	@Override
	public void sendAudio(byte[] frame) {
		audio.add(frame);
	}

	@Override
	public void close() {
		closed = true;
	}

	@Override
	public void closeForPolicyViolation() {
		policyClosed = true;
	}

	ServerMessage last() {
		return messages.getLast();
	}

	<T extends ServerMessage> List<T> all(Class<T> type) {
		return messages.stream().filter(type::isInstance).map(type::cast).toList();
	}

	void clear() {
		messages.clear();
		audio.clear();
	}
}
