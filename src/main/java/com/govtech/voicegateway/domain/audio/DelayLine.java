package com.govtech.voicegateway.domain.audio;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import lombok.extern.slf4j.Slf4j;

/**
 * 민원인 → 상담원 음성을 정해진 시간만큼 붙잡아 두었다가 내보내는 지연 버퍼 (통화마다 하나).
 * <p>
 * 아직 내보내지 않은 프레임은 {@link #mute}로 무음(0)으로 바꿀 수 있다. 위험 발화를 상담원이 듣기 전에 지우는 데 쓴다.
 * 시간은 밖에서 넣어 준다: 내보낼 때를 정하는 단조 시각(nanoTime)과, 차단 구간을 지정하는 벽시계 시각(epoch ms).
 * <p>
 * 내보내기는 잠금 안에서 한다. {@link AudioSink}는 막히지 않아야 하고(연결의 송신 큐에 넣기만 함),
 * 그래야 {@link #close()} 뒤에 프레임이 나가거나 두 스레드가 비울 때 순서가 섞이는 일이 없다.
 */
@Slf4j
public final class DelayLine {

	/** 버퍼 상한의 최솟값 (프레임 수) */
	static final int MIN_CAPACITY = 16;

	private final long delayNanos;
	private final int capacity;
	private final AudioSink target;
	private final Deque<Frame> pending = new ArrayDeque<>();
	private boolean closed;
	private long dropped;

	public DelayLine(Duration delay, AudioSink target) {
		this.delayNanos = delay.toNanos();
		// 실시간(20ms에 한 프레임)으로 들어오면 지연 시간만큼만 쌓인다. 두 배를 넘으면 비정상 송신으로 보고 버린다
		this.capacity = (int) Math.max(MIN_CAPACITY, delay.toMillis() / 10);
		this.target = target;
	}

	/**
	 * @param nowNanos         받은 시각 (단조 시각)
	 * @param receivedAtMillis 받은 시각 (epoch ms), 차단 구간 판단에 쓴다
	 */
	public synchronized void offer(byte[] frame, long nowNanos, long receivedAtMillis) {
		if (closed) {
			return;
		}
		if (delayNanos == 0) {
			target.accept(frame);
			return;
		}
		if (pending.size() >= capacity) {
			if (dropped++ == 0) {
				log.warn("지연 버퍼가 가득 차 프레임을 버립니다 (상한 {}프레임). 민원인이 실시간보다 빠르게 보내고 있습니다", capacity);
			}
			return;
		}
		pending.addLast(new Frame(frame, nowNanos + delayNanos, receivedAtMillis));
	}

	/** 내보낼 때가 된 프레임을 받은 순서대로 내보낸다. */
	public synchronized void drain(long nowNanos) {
		while (!pending.isEmpty() && pending.peekFirst().releaseAtNanos <= nowNanos) {
			target.accept(pending.pollFirst().data);
		}
	}

	/**
	 * 아직 내보내지 않은 프레임 중 받은 시각이 [fromMillis, toMillis] 안인 것을 무음으로 바꾼다.
	 *
	 * @return 새로 무음 처리한 프레임 수
	 */
	public synchronized int mute(long fromMillis, long toMillis) {
		int muted = 0;
		for (Frame frame : pending) {
			if (frame.receivedAtMillis >= fromMillis && frame.receivedAtMillis <= toMillis && frame.mute()) {
				muted++;
			}
		}
		return muted;
	}

	/** 아직 내보내지 않은 프레임을 모두 무음으로 바꾼다. */
	public int muteAll() {
		return mute(Long.MIN_VALUE, Long.MAX_VALUE);
	}

	/** 통화 종료: 남은 프레임은 버린다. */
	public synchronized void close() {
		closed = true;
		pending.clear();
	}

	public synchronized int pendingCount() {
		return pending.size();
	}

	/** 상한을 넘어 버린 프레임 수 */
	public synchronized long droppedCount() {
		return dropped;
	}

	private static final class Frame {
		private byte[] data;
		private final long releaseAtNanos;
		private final long receivedAtMillis;
		private boolean muted;

		private Frame(byte[] data, long releaseAtNanos, long receivedAtMillis) {
			this.data = data;
			this.releaseAtNanos = releaseAtNanos;
			this.receivedAtMillis = receivedAtMillis;
		}

		private boolean mute() {
			if (muted) {
				return false;
			}
			data = new byte[data.length];
			muted = true;
			return true;
		}
	}
}
