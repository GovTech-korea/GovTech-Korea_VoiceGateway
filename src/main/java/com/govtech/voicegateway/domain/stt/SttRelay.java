package com.govtech.voicegateway.domain.stt;

import org.springframework.stereotype.Component;

import com.govtech.voicegateway.domain.call.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 민원인 음성을 설정한 형식으로 바꿔 {@link SttGateway}로 넘긴다. 상담원 지연 버퍼보다 먼저 불러 STT가 바로 듣게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SttRelay {

	private final SttProperties properties;
	private final SttGateway gateway;

	public Session open(String callId) {
		return new Session(callId, properties.format(), gateway.open(callId, properties.format()));
	}

	/** 통화 하나의 전달 세션. 한 연결의 프레임은 차례로 들어오지만, 종료는 다른 스레드에서 올 수 있어 잠근다. */
	public static final class Session {

		private final String callId;
		private final SttAudioFormat format;
		private final SttStream stream;
		private final Pcm16Downsampler downsampler;
		private long sequence;
		private long failures;
		private boolean closed;

		private Session(String callId, SttAudioFormat format, SttStream stream) {
			this.callId = callId;
			this.format = format;
			this.stream = stream;
			this.downsampler = format == SttAudioFormat.PCM16_8K ? new Pcm16Downsampler() : null;
		}

		/** @param pcm16k 앱에서 받은 프레임 (PCM16 16kHz 20ms) */
		public synchronized void accept(byte[] pcm16k, long receivedAtEpochMs) {
			if (closed) {
				return;
			}
			byte[] pcm = downsampler == null ? pcm16k : downsampler.process(pcm16k);
			try {
				stream.send(new SttChunk(callId, Role.CITIZEN, sequence++, receivedAtEpochMs, format, pcm));
			} catch (RuntimeException e) {
				// STT 장애가 상담 통화를 끊으면 안 된다. 조각은 버리고 순번은 건너뛴 채로 둔다
				if (failures++ == 0) {
					log.warn("STT 전달 실패: {} (이후 같은 통화의 실패는 종료 때 개수만 남김)", callId, e);
				}
			}
		}

		public synchronized void close() {
			if (closed) {
				return;
			}
			closed = true;
			if (failures > 0) {
				log.warn("STT 전달 실패 합계: {} {}조각", callId, failures);
			}
			try {
				stream.close();
			} catch (RuntimeException e) {
				log.warn("STT 스트림 닫기 실패: {}", callId, e);
			}
		}
	}
}
