package com.govtech.voicegateway.domain.call;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

import com.govtech.voicegateway.domain.emotion.EmotionAnalyzer;
import com.govtech.voicegateway.domain.emotion.EmotionRequest;
import com.govtech.voicegateway.domain.emotion.EmotionResult;

/** 정해 둔 결과를 차례로 돌려주는 테스트용 감정 분석. 정해 둔 것이 없으면 평온(10점)으로 답한다. */
class FakeEmotionAnalyzer implements EmotionAnalyzer {

	private final Deque<EmotionResult> results = new ArrayDeque<>();
	int calls;
	byte[] lastPcm;
	EmotionRequest lastRequest;
	/** true면 결과를 바로 주지 않고 {@link #pending}에 쌓아 두었다가 테스트가 직접 완료시킨다 */
	boolean deferred;
	final java.util.List<CompletableFuture<EmotionResult>> pending = new java.util.ArrayList<>();

	void willReturn(EmotionResult... next) {
		results.addAll(java.util.List.of(next));
	}

	@Override
	public CompletableFuture<EmotionResult> analyze(EmotionRequest request) {
		calls++;
		lastPcm = request.pcm();
		lastRequest = request;
		if (deferred) {
			CompletableFuture<EmotionResult> future = new CompletableFuture<>();
			pending.add(future);
			return future;
		}
		EmotionResult result = results.isEmpty() ? new EmotionResult(10, false) : results.removeFirst();
		return CompletableFuture.completedFuture(result);
	}
}
