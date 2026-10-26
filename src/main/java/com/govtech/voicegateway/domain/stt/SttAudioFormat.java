package com.govtech.voicegateway.domain.stt;

/**
 * STT 엔진으로 보내는 음성 형식. 모두 헤더 없는 PCM16 little-endian 모노 바이트다.
 */
public enum SttAudioFormat {

	/** 앱에서 받은 그대로 */
	PCM16_16K(16_000),
	/** 16kHz → 8kHz 다운샘플 (저역 통과 필터 후 2:1 솎아내기). 전화망 음성용 STT 모델에 맞춘다 */
	PCM16_8K(8_000);

	private final int sampleRate;

	SttAudioFormat(int sampleRate) {
		this.sampleRate = sampleRate;
	}

	public int sampleRate() {
		return sampleRate;
	}

	/** 20ms 분량의 바이트 수 */
	public int frameBytes() {
		return sampleRate / 50 * 2;
	}
}
