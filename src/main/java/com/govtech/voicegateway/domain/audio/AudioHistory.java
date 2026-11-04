package com.govtech.voicegateway.domain.audio;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 최근에 받은 음성 프레임을 받은 시각과 함께 일정 개수만 들고 있다가, 시각 구간으로 꺼내 준다.
 * 발화가 끝난 뒤 그 발화의 음성을 감정 분석에 보낼 때 쓴다.
 */
public final class AudioHistory {

	private final int maxFrames;
	private final Deque<Entry> frames = new ArrayDeque<>();

	public AudioHistory(int maxFrames) {
		this.maxFrames = maxFrames;
	}

	public synchronized void add(byte[] frame, long receivedEpochMs) {
		frames.addLast(new Entry(frame, receivedEpochMs));
		if (frames.size() > maxFrames) {
			frames.removeFirst();
		}
	}

	/** fromEpochMs 이상 toEpochMs 이하에 받은 프레임을 이어 붙인다 */
	public synchronized byte[] between(long fromEpochMs, long toEpochMs) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Entry entry : frames) {
			if (entry.receivedEpochMs >= fromEpochMs && entry.receivedEpochMs <= toEpochMs) {
				out.writeBytes(entry.frame);
			}
		}
		return out.toByteArray();
	}

	private record Entry(byte[] frame, long receivedEpochMs) {
	}
}
