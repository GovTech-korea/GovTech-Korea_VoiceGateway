package com.govtech.voicegateway.domain.stt.streaming;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import com.govtech.voicegateway.domain.stt.SttAudioFormat;
import com.govtech.voicegateway.domain.stt.SttChunk;
import com.govtech.voicegateway.domain.stt.SttEndpoint;
import com.govtech.voicegateway.domain.stt.SttGateway;
import com.govtech.voicegateway.domain.stt.SttListener;
import com.govtech.voicegateway.domain.stt.SttStream;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * STT 담당 서버(streaming-asr1, NeMo nemotron 스트리밍)의 {@code /ws/transcribe}에 통화마다 하나씩 연결한다.
 * 주소가 정해지지 않았으면 아무것도 하지 않는다.
 * <p>
 * 서버 규격: 16kHz 모노 int16 little-endian PCM을 바이너리로 보내고(100ms 단위 권장),
 * {@code ready}, {@code speech_start}, {@code partial}, {@code final}, {@code discard}, {@code error} JSON을 받는다.
 */
@Slf4j
@Component
public class StreamingAsrGateway implements SttGateway {

	private static final SttStream NONE = new SttStream() {
		@Override
		public void send(SttChunk chunk) {
		}

		@Override
		public void close() {
		}
	};

	private final SttEndpoint endpoint;
	private final JsonMapper jsonMapper;
	private final WebSocketClient client;

	@Autowired
	public StreamingAsrGateway(SttEndpoint endpoint, JsonMapper jsonMapper) {
		this(endpoint, jsonMapper, new StandardWebSocketClient());
	}

	StreamingAsrGateway(SttEndpoint endpoint, JsonMapper jsonMapper, WebSocketClient client) {
		this.endpoint = endpoint;
		this.jsonMapper = jsonMapper;
		this.client = client;
	}

	@Override
	public SttStream open(String callId, SttAudioFormat format, SttListener listener) {
		return endpoint.current()
				.map(uri -> {
					if (format != SttAudioFormat.PCM16_16K) {
						log.warn("STT 서버는 16kHz만 받습니다. voicegateway.stt.format={}이라 {}는 STT 서버로 보내지 않습니다", format, callId);
						return NONE;
					}
					return (SttStream) new StreamingAsrStream(callId, uri, listener, jsonMapper, client);
				})
				.orElse(NONE);
	}
}
