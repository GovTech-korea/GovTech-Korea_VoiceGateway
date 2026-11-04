package com.govtech.voicegateway.domain.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * PCM16 LE 16kHz mono 음성에 WAV 헤더(44바이트)를 붙인다. 앱·브라우저의 일반 미디어 플레이어로 재생할 때 쓴다.
 */
public final class Wav {

	private static final int SAMPLE_RATE = 16_000;

	private Wav() {
	}

	public static byte[] fromPcm16k(byte[] pcm) {
		ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
		wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
				.put("WAVE".getBytes(StandardCharsets.US_ASCII))
				.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
				.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16)
				.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length)
				.put(pcm);
		return wav.array();
	}
}
