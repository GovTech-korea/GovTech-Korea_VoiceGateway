package com.govtech.voicegateway.domain.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.govtech.voicegateway.domain.call.Role;

class SttRelayTest {

	private final FakeSttGateway gateway = new FakeSttGateway();

	private SttRelay relay(SttAudioFormat format) {
		return new SttRelay(new SttProperties(format, null), gateway);
	}

	private static byte[] frame(int value) {
		byte[] frame = new byte[640];
		Arrays.fill(frame, (byte) value);
		return frame;
	}

	@Test
	@DisplayName("PCM16_16K면 받은 바이트를 그대로, 통화 ID·화자·순번·시각과 함께 보낸다")
	void passThrough16k() {
		SttRelay.Session session = relay(SttAudioFormat.PCM16_16K).open("call-1");

		session.accept(frame(1), 1_000);
		session.accept(frame(2), 1_020);

		assertThat(gateway.chunks("call-1")).extracting(SttChunk::callId, SttChunk::speaker, SttChunk::sequence,
				SttChunk::receivedAtEpochMs, SttChunk::format)
				.containsExactly(
						tuple("call-1", Role.CITIZEN, 0L, 1_000L, SttAudioFormat.PCM16_16K),
						tuple("call-1", Role.CITIZEN, 1L, 1_020L, SttAudioFormat.PCM16_16K));
		assertThat(gateway.chunks("call-1").get(1).pcm()).isEqualTo(frame(2));
	}

	@Test
	@DisplayName("PCM16_8K면 20ms 조각이 320바이트가 된다")
	void downsamples8k() {
		SttRelay.Session session = relay(SttAudioFormat.PCM16_8K).open("call-1");

		session.accept(new byte[640], 1_000);

		SttChunk chunk = gateway.chunks("call-1").getFirst();
		assertThat(chunk.pcm()).hasSize(SttAudioFormat.PCM16_8K.frameBytes()).hasSize(320);
		assertThat(chunk.format()).isEqualTo(SttAudioFormat.PCM16_8K);
	}

	@Test
	@DisplayName("STT 쪽에서 예외가 나도 밖으로 던지지 않는다 (상담 통화는 계속)")
	void swallowsSttFailure() {
		SttGateway failing = (callId, format) -> new SttStream() {
			@Override
			public void send(SttChunk chunk) {
				throw new IllegalStateException("STT 연결 끊김");
			}

			@Override
			public void close() {
				throw new IllegalStateException("STT 연결 끊김");
			}
		};
		SttRelay.Session session = new SttRelay(new SttProperties(SttAudioFormat.PCM16_16K, null), failing).open("call-1");

		assertThatCode(() -> {
			session.accept(frame(1), 1_000);
			session.accept(frame(2), 1_020);
			session.close();
		}).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("닫은 뒤에는 보내지 않고, 여러 번 닫아도 스트림은 한 번만 닫는다")
	void closeOnce() {
		SttRelay.Session session = relay(SttAudioFormat.PCM16_16K).open("call-1");

		session.close();
		session.close();
		session.accept(frame(1), 1_000);

		assertThat(gateway.closeCount("call-1")).isEqualTo(1);
		assertThat(gateway.chunks("call-1")).isEmpty();
	}
}
