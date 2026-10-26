package com.govtech.voicegateway.domain.call;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ScheduledRingTimer implements RingTimer {

	private final ScheduledExecutorService executor =
			Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("ring-timer").daemon().factory());

	@Override
	public Cancellable schedule(Duration delay, Runnable task) {
		ScheduledFuture<?> future = executor.schedule(() -> {
			try {
				task.run();
			} catch (RuntimeException e) {
				log.error("벨 타이머 작업 실패", e);
			}
		}, delay.toMillis(), TimeUnit.MILLISECONDS);
		return () -> future.cancel(false);
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}
}
