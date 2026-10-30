package com.govtech.voicegateway.domain.audio;

import java.util.List;

/**
 * 미리 만든 음성(안내 멘트)을 실시간 속도(20ms에 한 프레임)로 내보낸다. {@link DelayLines}의 틱이 진행시킨다.
 * 한꺼번에 보내면 앱의 재생 버퍼가 넘치거나 상담원 음성과 섞이므로 실제 통화처럼 흘려 보낸다.
 */
public final class Playback {

	static final long FRAME_NANOS = 20_000_000L;

	private final List<byte[]> frames;
	private final AudioSink target;
	private final long startNanos;
	private int next;
	private boolean stopped;

	Playback(List<byte[]> frames, AudioSink target, long startNanos) {
		this.frames = frames;
		this.target = target;
		this.startNanos = startNanos;
	}

	/** 내보낼 때가 된 프레임을 차례로 내보낸다 */
	synchronized void drain(long nowNanos) {
		while (!stopped && next < frames.size() && startNanos + next * FRAME_NANOS <= nowNanos) {
			target.accept(frames.get(next++));
		}
	}

	public synchronized boolean isPlaying() {
		return !stopped && next < frames.size();
	}

	/** 남은 프레임을 버린다 (통화가 끝났을 때) */
	public synchronized void stop() {
		stopped = true;
	}
}
