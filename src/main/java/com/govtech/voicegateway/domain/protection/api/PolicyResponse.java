package com.govtech.voicegateway.domain.protection.api;

import com.govtech.voicegateway.domain.protection.ProtectionPolicy;
import com.govtech.voicegateway.domain.protection.ProtectionPolicy.AfterNotice;

public record PolicyResponse(int abuseLimit, AfterNotice afterNotice, int afterNoticeLimit, int agitationSeconds) {

	public static PolicyResponse from(ProtectionPolicy policy) {
		return new PolicyResponse(policy.abuseLimit(), policy.afterNotice(), policy.afterNoticeLimit(),
				policy.agitationSeconds());
	}
}
