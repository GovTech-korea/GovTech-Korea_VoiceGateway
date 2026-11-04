package com.govtech.voicegateway.domain.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WavTest {

	@Test
	@DisplayName("16kHz mono 16비트 WAV 헤더를 붙이고 PCM은 그대로 둔다")
	void header() {
		byte[] pcm = {1, 2, 3, 4};
		byte[] wav = Wav.fromPcm16k(pcm);
		ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

		assertThat(wav).hasSize(48);
		assertThat(buffer.getShort(22)).isEqualTo((short) 1);
		assertThat(buffer.getInt(24)).isEqualTo(16_000);
		assertThat(buffer.getInt(40)).isEqualTo(4);
		assertThat(wav).endsWith(1, 2, 3, 4);
	}

	@Test
	@DisplayName("멘트 프레임을 이어 붙인 PCM을 돌려준다")
	void announcementPcm() {
		byte[] a = new byte[640];
		byte[] b = new byte[640];
		b[0] = 7;

		assertThat(new Announcement(List.of(a, b)).pcm()).hasSize(1280).satisfies(pcm -> assertThat(pcm[640]).isEqualTo((byte) 7));
	}
}
