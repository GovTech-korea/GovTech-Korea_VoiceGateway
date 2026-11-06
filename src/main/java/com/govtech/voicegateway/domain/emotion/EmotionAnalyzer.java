package com.govtech.voicegateway.domain.emotion;

import java.util.concurrent.CompletableFuture;

/**
 * 민원인 음성 구간의 감정을 판단한다. 통화 스레드를 막지 않도록 결과는 나중에 돌려준다.
 */
public interface EmotionAnalyzer {

	CompletableFuture<EmotionResult> analyze(EmotionRequest request);
}
