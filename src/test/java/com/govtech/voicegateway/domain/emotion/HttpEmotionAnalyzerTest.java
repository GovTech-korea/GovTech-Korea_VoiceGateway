package com.govtech.voicegateway.domain.emotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpEmotionAnalyzerTest {

	@Test
	@DisplayName("발화 음성을 16kHz mono 16비트 WAV로 감싼다")
	void wavHeader() {
		byte[] wav = HttpEmotionAnalyzer.wav(new byte[640]);
		ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

		assertThat(wav).hasSize(44 + 640);
		assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
		assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
		assertThat(header.getShort(22)).isEqualTo((short) 1); // mono
		assertThat(header.getInt(24)).isEqualTo(16_000);
		assertThat(header.getShort(34)).isEqualTo((short) 16);
		assertThat(header.getInt(40)).isEqualTo(640);
	}

	@Test
	@DisplayName("점수는 0~100으로 자르고 기준 점수 이상이면 격양이다")
	void result() {
		assertThat(EmotionResult.of(130, 70)).isEqualTo(new EmotionResult(100, true));
		assertThat(EmotionResult.of(69, 70)).isEqualTo(new EmotionResult(69, false));
	}
}
