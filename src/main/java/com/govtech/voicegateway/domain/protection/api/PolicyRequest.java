package com.govtech.voicegateway.domain.protection.api;

import com.govtech.voicegateway.domain.protection.ProtectionPolicy;
import com.govtech.voicegateway.domain.protection.ProtectionPolicy.AfterNotice;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 웹 정책설정 화면이 저장할 때 보내는 값. 범위는 화면의 선택 범위와 같다.
 */
public record PolicyRequest(
		@NotNull @Min(1) @Max(10) Integer abuseLimit,
		@NotNull AfterNotice afterNotice,
		@NotNull @Min(1) @Max(10) Integer afterNoticeLimit,
		@NotNull @Min(3) @Max(60) Integer agitationSeconds) {

	public ProtectionPolicy toPolicy() {
		return new ProtectionPolicy(abuseLimit, afterNotice, afterNoticeLimit, agitationSeconds);
	}
}
