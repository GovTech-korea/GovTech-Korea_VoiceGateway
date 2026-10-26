package com.govtech.voicegateway.domain.call;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 시간을 직접 진행시키는 테스트용 벨 타이머. {@link #fireAll()}로 대기 중인 타이머를 모두 실행한다. */
class FakeRingTimer implements RingTimer {

	private final List<Scheduled> scheduled = new ArrayList<>();

	@Override
	public Cancellable schedule(Duration delay, Runnable task) {
		Scheduled entry = new Scheduled(delay, task);
		scheduled.add(entry);
		return () -> entry.cancelled = true;
	}

	void fireAll() {
		List<Scheduled> pending = scheduled.stream().filter(s -> !s.cancelled && !s.fired).toList();
		pending.forEach(s -> {
			s.fired = true;
			s.task.run();
		});
	}

	long activeCount() {
		return scheduled.stream().filter(s -> !s.cancelled && !s.fired).count();
	}

	Duration lastDelay() {
		return scheduled.getLast().delay;
	}

	private static final class Scheduled {
		private final Duration delay;
		private final Runnable task;
		private boolean cancelled;
		private boolean fired;

		private Scheduled(Duration delay, Runnable task) {
			this.delay = delay;
			this.task = task;
		}
	}
}
