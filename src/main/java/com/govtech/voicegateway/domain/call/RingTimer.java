package com.govtech.voicegateway.domain.call;

import java.time.Duration;

/**
 * 벨 미응답 타이머. 테스트에서 시간을 직접 진행시킬 수 있도록 분리했다.
 */
public interface RingTimer {

	Cancellable schedule(Duration delay, Runnable task);

	interface Cancellable {
		void cancel();
	}
}
