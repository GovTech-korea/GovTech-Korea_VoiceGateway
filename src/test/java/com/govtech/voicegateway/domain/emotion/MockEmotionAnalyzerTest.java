package com.govtech.voicegateway.domain.emotion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockEmotionAnalyzerTest {

	private final MockEmotionAnalyzer analyzer = new MockEmotionAnalyzer(70);

	private static byte[] tone(int amplitude, int samples) {
		byte[] pcm = new byte[samples * 2];
		for (int i = 0; i < samples; i++) {
			short value = (short) (i % 2 == 0 ? amplitude : -amplitude);
			pcm[2 * i] = (byte) value;
			pcm[2 * i + 1] = (byte) (value >> 8);
		}
		return pcm;
	}

	@Test
	@DisplayName("조용히 차분하게 말하면 격양이 아니다")
	void calm() {
		EmotionResult result = analyzer.analyze(tone(300, 16_000), "가로등이 꺼져 있어요").join();

		assertThat(result.agitated()).isFalse();
		assertThat(result.score()).isLessThan(30);
	}

	@Test
	@DisplayName("크게 소리치며 재촉하면 격양이다")
	void loudAndAngry() {
		EmotionResult result = analyzer.analyze(tone(16_000, 16_000), "도대체 언제까지 기다리게 하는 거예요!").join();

		assertThat(result.agitated()).isTrue();
		assertThat(result.score()).isBetween(70, 100);
	}

	@Test
	@DisplayName("무음은 소리 점수가 0이다")
	void silence() {
		assertThat(MockEmotionAnalyzer.loudnessScore(new byte[640])).isZero();
		assertThat(MockEmotionAnalyzer.textScore(null)).isZero();
	}
}
