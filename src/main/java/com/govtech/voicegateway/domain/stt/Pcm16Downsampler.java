package com.govtech.voicegateway.domain.stt;

/**
 * PCM16 little-endian 16kHz → 8kHz 변환기 (통화마다 하나, 스레드 안전하지 않음).
 * <p>
 * 그냥 한 샘플씩 건너뛰면 4kHz 넘는 소리가 낮은 소리로 접혀 들어온다(에일리어싱).
 * 그래서 먼저 3.6kHz에서 자르는 저역 통과 FIR 필터(해밍 창 sinc, 31탭)를 거친 뒤 두 샘플마다 하나를 남긴다.
 * 필터는 프레임 경계를 넘어 이전 샘플을 기억하므로, 프레임을 나눠 넣어도 한 번에 넣은 것과 결과가 같다.
 */
public final class Pcm16Downsampler {

	static final int TAPS = 31;
	/** 차단 주파수 / 입력 샘플링 주파수 (3.6kHz / 16kHz) */
	private static final double CUTOFF = 3_600.0 / 16_000;
	private static final double[] COEFFICIENTS = lowPass();

	/** 최근 입력 샘플 (원형 버퍼) */
	private final double[] history = new double[TAPS];
	private int head;
	/** 다음 입력 샘플이 출력될 차례(짝수 번째)인지 */
	private boolean emitNext = true;

	private static double[] lowPass() {
		double[] h = new double[TAPS];
		int mid = TAPS / 2;
		double sum = 0;
		for (int i = 0; i < TAPS; i++) {
			int n = i - mid;
			double sinc = n == 0 ? 2 * CUTOFF : Math.sin(2 * Math.PI * CUTOFF * n) / (Math.PI * n);
			double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (TAPS - 1));
			h[i] = sinc * window;
			sum += h[i];
		}
		for (int i = 0; i < TAPS; i++) {
			h[i] /= sum; // 직류 이득 1: 무음은 무음, 일정한 값은 그대로
		}
		return h;
	}

	/**
	 * @param pcm16k 16kHz 샘플 (짝수 바이트)
	 * @return 8kHz 샘플. 입력 샘플 수의 절반
	 */
	public byte[] process(byte[] pcm16k) {
		int inSamples = pcm16k.length / 2;
		byte[] out = new byte[(inSamples + (emitNext ? 1 : 0)) / 2 * 2];
		int o = 0;
		for (int i = 0; i < inSamples; i++) {
			short sample = (short) ((pcm16k[2 * i] & 0xFF) | (pcm16k[2 * i + 1] << 8));
			history[head] = sample;
			head = (head + 1) % TAPS;
			if (emitNext) {
				int value = (int) Math.round(filtered());
				value = Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
				out[o++] = (byte) value;
				out[o++] = (byte) (value >> 8);
			}
			emitNext = !emitNext;
		}
		return out;
	}

	private double filtered() {
		double acc = 0;
		// head는 가장 오래된 샘플 자리. 계수가 대칭이라 방향은 상관없다
		for (int k = 0; k < TAPS; k++) {
			acc += COEFFICIENTS[k] * history[(head + k) % TAPS];
		}
		return acc;
	}
}
