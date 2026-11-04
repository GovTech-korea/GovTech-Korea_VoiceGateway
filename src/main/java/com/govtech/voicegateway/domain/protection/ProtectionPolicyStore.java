package com.govtech.voicegateway.domain.protection;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * 보호 정책 하나를 메모리에 둔다. MVP는 상담원이 한 명이라 모든 통화에 같은 정책을 쓴다.
 * 데이터베이스가 없어 서버를 다시 띄우면 기본값으로 돌아간다.
 */
@Component
public class ProtectionPolicyStore {

	private final AtomicReference<ProtectionPolicy> policy = new AtomicReference<>(ProtectionPolicy.DEFAULT);

	public ProtectionPolicy get() {
		return policy.get();
	}

	public ProtectionPolicy save(ProtectionPolicy newPolicy) {
		policy.set(newPolicy);
		return newPolicy;
	}

	public ProtectionPolicy reset() {
		return save(ProtectionPolicy.DEFAULT);
	}
}
