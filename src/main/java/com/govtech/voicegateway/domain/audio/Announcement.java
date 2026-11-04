package com.govtech.voicegateway.domain.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.Resource;

/**
 * 민원인에게 들려줄 안내 음성. PCM16 LE 16kHz mono 원본을 20ms(640바이트) 프레임으로 나눠 둔다 (마지막 프레임은 무음으로 채움).
 */
public record Announcement(List<byte[]> frames) {

	static final int FRAME_BYTES = 640;

	public Announcement {
		frames = List.copyOf(frames);
	}

	public static Announcement load(Resource pcm) {
		try (InputStream in = pcm.getInputStream()) {
			return fromPcm(in.readAllBytes());
		} catch (IOException e) {
			throw new UncheckedIOException("안내 음성을 읽을 수 없습니다: " + pcm, e);
		}
	}

	static Announcement fromPcm(byte[] pcm) {
		List<byte[]> frames = new ArrayList<>();
		for (int offset = 0; offset < pcm.length; offset += FRAME_BYTES) {
			frames.add(Arrays.copyOfRange(pcm, offset, offset + FRAME_BYTES)); // 끝을 넘는 부분은 0으로 채워진다
		}
		return new Announcement(frames);
	}

	public long durationMs() {
		return frames.size() * 20L;
	}

	/** 프레임을 이어 붙인 PCM 전체 */
	public byte[] pcm() {
		byte[] pcm = new byte[frames.size() * FRAME_BYTES];
		for (int i = 0; i < frames.size(); i++) {
			System.arraycopy(frames.get(i), 0, pcm, i * FRAME_BYTES, FRAME_BYTES);
		}
		return pcm;
	}
}
