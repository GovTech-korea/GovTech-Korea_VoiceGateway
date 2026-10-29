package com.govtech.voicegateway.domain.stt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 받은 조각을 통화별로 모아 두는 테스트용 STT 연결부.
 */
public class FakeSttGateway implements SttGateway {

	private final Map<String, Stream> streams = new HashMap<>();

	private final Map<String, SttListener> listeners = new HashMap<>();

	@Override
	public SttStream open(String callId, SttAudioFormat format, SttListener listener) {
		Stream stream = new Stream();
		streams.put(callId, stream);
		listeners.put(callId, listener);
		return stream;
	}

	/** STT 서버가 결과를 돌려준 것처럼 리스너를 부를 때 쓴다 */
	public SttListener listener(String callId) {
		return listeners.get(callId);
	}

	public List<SttChunk> chunks(String callId) {
		return streams.get(callId).chunks;
	}

	public boolean isClosed(String callId) {
		return streams.get(callId).closeCount > 0;
	}

	public int closeCount(String callId) {
		return streams.get(callId).closeCount;
	}

	private static final class Stream implements SttStream {

		private final List<SttChunk> chunks = new ArrayList<>();
		private int closeCount;

		@Override
		public void send(SttChunk chunk) {
			chunks.add(chunk);
		}

		@Override
		public void close() {
			closeCount++;
		}
	}
}
