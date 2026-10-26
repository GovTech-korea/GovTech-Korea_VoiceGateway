package com.govtech.voicegateway.domain.call.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.govtech.voicegateway.domain.call.CallCoordinator;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 통화 음성 제어. 위험 판단(STT 결과)이나 테스트 페이지가 부른다.
 */
@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallAudioController {

	private final CallCoordinator coordinator;

	/**
	 * 민원인 음성 중 아직 상담원에게 나가지 않은 구간을 무음으로 바꾼다.
	 */
	@PostMapping("/{callId}/mute")
	public MuteResponse mute(@PathVariable String callId, @Valid @RequestBody(required = false) MuteRequest request) {
		MuteRequest range = request == null ? MuteRequest.ALL_PENDING : request;
		int muted = coordinator.mute(callId, range.fromEpochMs(), range.toEpochMs())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "통화 중인 callId가 아닙니다: " + callId));
		return new MuteResponse(callId, muted);
	}
}
