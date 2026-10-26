package com.govtech.voicegateway.domain.audio;

/**
 * 음성 프레임(PCM16 LE 16kHz mono 20ms)을 받는 곳. 호출한 스레드를 막지 않아야 한다.
 */
@FunctionalInterface
public interface AudioSink {

	void accept(byte[] frame);
}
