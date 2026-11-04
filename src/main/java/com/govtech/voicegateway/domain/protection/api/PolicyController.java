package com.govtech.voicegateway.domain.protection.api;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.govtech.voicegateway.domain.protection.ProtectionPolicyStore;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 보호 정책 조회·저장·초기화. 웹 정책설정 화면이 부른다. 저장한 값은 다음 통화부터 적용된다.
 * 웹은 다른 주소에서 브라우저로 부르므로 voicegateway.policy.allowed-origins의 출처를 허용한다 (인증 정보는 쓰지 않음).
 */
@CrossOrigin(origins = "${voicegateway.policy.allowed-origins:*}")
@RestController
@RequestMapping("/api/v1/policy")
@RequiredArgsConstructor
public class PolicyController {

	private final ProtectionPolicyStore store;

	@GetMapping
	public PolicyResponse get() {
		return PolicyResponse.from(store.get());
	}

	@PutMapping
	public PolicyResponse save(@Valid @RequestBody PolicyRequest request) {
		return PolicyResponse.from(store.save(request.toPolicy()));
	}

	/** 게이트웨이의 기본값으로 되돌린다 */
	@DeleteMapping
	public PolicyResponse reset() {
		return PolicyResponse.from(store.reset());
	}
}
