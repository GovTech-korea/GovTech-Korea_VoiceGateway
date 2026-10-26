package com.govtech.voicegateway.domain.stt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Pcm16DownsamplerTest {

	private static final int SAMPLES_PER_FRAME = 320;

	private final Pcm16Downsampler downsampler = new Pcm16Downsampler();

	/** 16kHz 사인파 PCM16 little-endian */
	private static byte[] tone(double hz, int amplitude, int samples, int offset) {
		byte[] pcm = new byte[samples * 2];
		for (int i = 0; i < samples; i++) {
			int value = (int) Math.round(amplitude * Math.sin(2 * Math.PI * hz * (i + offset) / 16_000));
			pcm[2 * i] = (byte) value;
			pcm[2 * i + 1] = (byte) (value >> 8);
		}
		return pcm;
	}

	private static byte[] constant(int value, int samples) {
		byte[] pcm = new byte[samples * 2];
		for (int i = 0; i < samples; i++) {
			pcm[2 * i] = (byte) value;
			pcm[2 * i + 1] = (byte) (value >> 8);
		}
		return pcm;
	}

	private static short sample(byte[] pcm, int index) {
		return (short) ((pcm[2 * index] & 0xFF) | (pcm[2 * index + 1] << 8));
	}

	/** 필터가 채워진 뒤(앞쪽 TAPS 샘플 제외)의 RMS */
	private static double rms(byte[] pcm) {
		int samples = pcm.length / 2;
		double sum = 0;
		int counted = 0;
		for (int i = Pcm16Downsampler.TAPS; i < samples; i++) {
			double s = sample(pcm, i);
			sum += s * s;
			counted++;
		}
		return Math.sqrt(sum / counted);
	}

	@Test
	@DisplayName("20ms 프레임 640바이트(320샘플)가 320바이트(160샘플)가 된다")
	void halvesFrame() {
		byte[] out = downsampler.process(new byte[640]);

		assertThat(out).hasSize(320);
	}

	@Test
	@DisplayName("무음은 무음으로 나온다")
	void silenceStaysSilent() {
		assertThat(downsampler.process(new byte[640])).isEqualTo(new byte[320]);
	}

	@Test
	@DisplayName("일정한 값(직류)은 필터가 채워진 뒤 같은 값으로, little-endian으로 나온다")
	void keepsDcLittleEndian() {
		byte[] out = downsampler.process(constant(1000, SAMPLES_PER_FRAME));

		assertThat(sample(out, 159)).isEqualTo((short) 1000);
		assertThat(out[318]).isEqualTo((byte) 0xE8); // 1000 = 0x03E8, 낮은 바이트 먼저
		assertThat(out[319]).isEqualTo((byte) 0x03);
	}

	@Test
	@DisplayName("음수 값도 부호를 지켜 변환한다")
	void keepsNegative() {
		byte[] out = downsampler.process(constant(-1234, SAMPLES_PER_FRAME));

		assertThat(sample(out, 159)).isEqualTo((short) -1234);
	}

	@Test
	@DisplayName("1kHz(통과 대역)는 거의 그대로, 6kHz(8kHz로는 표현 못 해 접혀 들어올 소리)는 크게 줄인다")
	void filtersAliasing() {
		byte[] pass = new Pcm16Downsampler().process(tone(1_000, 10_000, 3_200, 0));
		byte[] stop = new Pcm16Downsampler().process(tone(6_000, 10_000, 3_200, 0));

		double inputRms = 10_000 / Math.sqrt(2);
		assertThat(rms(pass) / inputRms).isBetween(0.95, 1.05);
		assertThat(rms(stop) / inputRms).isLessThan(0.01); // -40dB 이하
	}

	@Test
	@DisplayName("프레임으로 나눠 넣어도 한 번에 넣은 결과와 같다 (필터 상태를 이어 간다)")
	void continuousAcrossFrames() throws Exception {
		byte[] whole = new Pcm16Downsampler().process(tone(440, 8_000, SAMPLES_PER_FRAME * 5, 0));

		ByteArrayOutputStream chunked = new ByteArrayOutputStream();
		for (int f = 0; f < 5; f++) {
			chunked.write(downsampler.process(tone(440, 8_000, SAMPLES_PER_FRAME, f * SAMPLES_PER_FRAME)));
		}

		assertThat(chunked.toByteArray()).isEqualTo(whole);
	}

	@Test
	@DisplayName("홀수 샘플 프레임이 와도 두 샘플마다 하나씩 이어서 남긴다")
	void oddSampleCounts() {
		byte[] first = downsampler.process(new byte[6]); // 3샘플 → 0, 2번째
		byte[] second = downsampler.process(new byte[6]); // 3샘플 → 4번째(=두 번째 프레임의 1번째)

		assertThat(first).hasSize(4);
		assertThat(second).hasSize(2);
	}

	@Test
	@DisplayName("최댓값 근처에서도 넘치지 않는다")
	void clamps() {
		byte[] out = downsampler.process(constant(Short.MAX_VALUE, SAMPLES_PER_FRAME));

		assertThat(sample(out, 159)).isEqualTo(Short.MAX_VALUE);
	}
}
