package com.govtech.voicegateway.domain.call.api;

import jakarta.validation.constraints.NotNull;

/**
 * @param hold true면 차단 유지 켜기, false면 끄기
 */
public record MuteHoldRequest(@NotNull Boolean hold) {
}
