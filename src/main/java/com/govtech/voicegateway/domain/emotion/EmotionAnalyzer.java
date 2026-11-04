package com.govtech.voicegateway.domain.emotion;

import java.util.concurrent.CompletableFuture;

/**
 * 민원인 발화 하나의 감정을 판단한다. 통화 스레드를 막지 않도록 결과는 나중에 돌려준다.
 */
public interface EmotionAnalyzer {

	/**
	 * @param pcm  그 발화 구간의 민원인 음성 (PCM16 LE 16kHz mono)
	 * @param text 그 발화의 STT 확정 문장
	 */
	CompletableFuture<EmotionResult> analyze(byte[] pcm, String text);
}
