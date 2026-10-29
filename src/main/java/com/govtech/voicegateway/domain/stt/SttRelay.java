package com.govtech.voicegateway.domain.stt;

import java.util.List;

import org.springframework.stereotype.Component;

import com.govtech.voicegateway.domain.call.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 민원인 음성을 설정한 형식으로 바꿔 등록된 모든 {@link SttGateway}(기록, STT 서버 등)로 넘긴다.
 * 상담원 지연 버퍼보다 먼저 불러 STT가 바로 듣게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SttRelay {

	private final SttProperties properties;
	private final List<SttGateway> gateways;

	/** @param listener STT 결과(자막, 위험 구간, 차단 유지)를 받을 곳 */
	public Session open(String callId, SttListener listener) {
		List<SttStream> streams = gateways.stream()
				.map(gateway -> gateway.open(callId, properties.format(), listener))
				.toList();
		return new Session(callId, properties.format(), streams);
	}

	/**
	 * 통화 하나의 전달 세션. 한 연결의 프레임은 차례로 들어오지만, 종료는 다른 스레드에서 올 수 있어 잠근다.
	 * 집계 값은 잠금 없이 읽게 volatile로 둔다 (현황 조회가 느린 STT 전송을 기다리지 않게).
	 */
	public static final class Session {

		private final String callId;
		private final SttAudioFormat format;
		private final List<SttStream> streams;
		private final Pcm16Downsampler downsampler;
		private volatile long sequence;
		private volatile long bytes;
		private volatile long failures;
		private boolean closed;

		private Session(String callId, SttAudioFormat format, List<SttStream> streams) {
			this.callId = callId;
			this.format = format;
			this.streams = streams;
			this.downsampler = format == SttAudioFormat.PCM16_8K ? new Pcm16Downsampler() : null;
		}

		/** @param pcm16k 앱에서 받은 프레임 (PCM16 16kHz 20ms) */
		public synchronized void accept(byte[] pcm16k, long receivedAtEpochMs) {
			if (closed) {
				return;
			}
			byte[] pcm = downsampler == null ? pcm16k : downsampler.process(pcm16k);
			long seq = sequence;
			sequence = seq + 1; // 잠금 안에서만 쓰므로 volatile 증가가 안전하다
			SttChunk chunk = new SttChunk(callId, Role.CITIZEN, seq, receivedAtEpochMs, format, pcm);
			boolean failed = false;
			for (SttStream stream : streams) {
				// 한 연결부의 장애가 다른 연결부(기록, STT 서버)나 상담 통화에 번지지 않게 따로 막는다
				try {
					stream.send(chunk);
				} catch (RuntimeException e) {
					failed = true;
					if (failures == 0) {
						log.warn("STT 전달 실패: {} (이후 같은 통화의 실패는 종료 때 개수만 남김)", callId, e);
					}
				}
			}
			if (failed) {
				failures = failures + 1;
			} else {
				bytes = bytes + pcm.length;
			}
		}

		/** 넘긴 조각 수 (실패 포함, 다음 순번과 같다) */
		public long chunkCount() {
			return sequence;
		}

		/** 전달에 성공한 바이트 수 (변환 후) */
		public long byteCount() {
			return bytes;
		}

		/** 전달에 실패한 조각 수 */
		public long failureCount() {
			return failures;
		}

		public synchronized void close() {
			if (closed) {
				return;
			}
			closed = true;
			if (failures > 0) {
				log.warn("STT 전달 실패 합계: {} {}조각", callId, failures);
			}
			for (SttStream stream : streams) {
				try {
					stream.close();
				} catch (RuntimeException e) {
					log.warn("STT 스트림 닫기 실패: {}", callId, e);
				}
			}
		}
	}
}
