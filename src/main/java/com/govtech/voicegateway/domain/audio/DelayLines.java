package com.govtech.voicegateway.domain.audio;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 통화별 {@link DelayLine}을 만들고, {@link #TICK_MS}마다 모두 확인해 때가 된 프레임을 내보낸다.
 * 테스트에서는 시각을 직접 넣고 {@link #drainAll()}을 불러 진행시킨다 (틱 스레드는 스프링이 띄울 때만 시작).
 */
@Slf4j
@Component
public class DelayLines {

	/** 프레임(20ms)보다 충분히 짧게 확인해 지연 오차를 줄인다 */
	static final long TICK_MS = 5;

	private final Set<DelayLine> lines = ConcurrentHashMap.newKeySet();
	private final LongSupplier nanoClock;
	private ScheduledExecutorService ticker;

	public DelayLines() {
		this(System::nanoTime);
	}

	public DelayLines(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
	}

	public DelayLine open(Duration delay, AudioSink target) {
		DelayLine line = new DelayLine(delay, target);
		lines.add(line);
		return line;
	}

	public void close(DelayLine line) {
		lines.remove(line);
		line.close();
	}

	public long nowNanos() {
		return nanoClock.getAsLong();
	}

	public void drainAll() {
		long now = nowNanos();
		for (DelayLine line : lines) {
			line.drain(now);
		}
	}

	@PostConstruct
	void start() {
		ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("audio-delay").daemon().factory());
		ticker.scheduleAtFixedRate(() -> {
			try {
				drainAll();
			} catch (Throwable e) {
				// 예외가 밖으로 나가면 이후 틱이 조용히 멈춰 모든 통화의 음성이 끊긴다
				log.error("지연 버퍼 처리 실패", e);
			}
		}, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
	}

	@PreDestroy
	void stop() {
		if (ticker != null) {
			ticker.shutdownNow();
		}
	}
}
