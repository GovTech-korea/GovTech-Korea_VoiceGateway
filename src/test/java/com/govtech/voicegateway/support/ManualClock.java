package com.govtech.voicegateway.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트에서 직접 진행시키는 시계. 벽시계(epoch ms)와 단조 시각(nanos)을 함께 움직인다.
 */
public class ManualClock extends Clock {

	private Instant now = Instant.parse("2026-10-26T13:00:00Z");
	private long nanos = 1_000_000_000L;

	public void advance(Duration duration) {
		now = now.plus(duration);
		nanos += duration.toNanos();
	}

	public long nanos() {
		return nanos;
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}
}
