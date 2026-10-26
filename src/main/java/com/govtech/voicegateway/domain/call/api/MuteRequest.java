package com.govtech.voicegateway.domain.call.api;

import jakarta.validation.constraints.AssertTrue;

/**
 * 차단 구간 (민원인 음성을 받은 시각, epoch ms). 둘 다 비우면 아직 나가지 않은 구간 전체를 차단한다.
 */
public record MuteRequest(Long fromEpochMs, Long toEpochMs) {

	static final MuteRequest ALL_PENDING = new MuteRequest(null, null);

	@AssertTrue(message = "fromEpochMs는 toEpochMs보다 클 수 없습니다")
	boolean isValidRange() {
		return fromEpochMs == null || toEpochMs == null || fromEpochMs <= toEpochMs;
	}
}
