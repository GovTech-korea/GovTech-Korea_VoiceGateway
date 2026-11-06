package com.govtech.voicegateway.domain.emotion;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 감정 모델이 준비되기 전까지 쓰는 목업. 목소리 크기와 문장(느낌표, 재촉·항의 표현)으로 점수를 낸다.
 * 앱 연동과 정책(격양 지속 시간) 시험용이며 실제 감정 판단이 아니다.
 */
public class MockEmotionAnalyzer implements EmotionAnalyzer {

	/** 이 크기(dBFS) 이하는 0점, {@link #LOUD_DBFS} 이상은 소리 점수 만점 */
	static final double QUIET_DBFS = -40;
	static final double LOUD_DBFS = -12;
	static final int LOUDNESS_POINTS = 60;

	private static final List<String> AGITATED_WORDS = List.of(
			"도대체", "언제까지", "왜 이렇게", "장난해", "말이 돼", "빨리", "책임", "어이가", "짜증", "화가", "답답", "몇 번");

	private final int agitatedScore;

	public MockEmotionAnalyzer(int agitatedScore) {
		this.agitatedScore = agitatedScore;
	}

	@Override
	public CompletableFuture<EmotionResult> analyze(EmotionRequest request) {
		return CompletableFuture.completedFuture(
				EmotionResult.of(loudnessScore(request.pcm()) + textScore(request.text()), agitatedScore,
						EmotionResult.Source.MOCK));
	}

	static int loudnessScore(byte[] pcm) {
		int samples = pcm.length / 2;
		if (samples == 0) {
			return 0;
		}
		double sum = 0;
		for (int i = 0; i < samples; i++) {
			short sample = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
			sum += (double) sample * sample;
		}
		double rms = Math.sqrt(sum / samples);
		if (rms < 1) {
			return 0;
		}
		double dbfs = 20 * Math.log10(rms / 32768);
		double ratio = (dbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS);
		return (int) Math.round(Math.clamp(ratio, 0, 1) * LOUDNESS_POINTS);
	}

	static int textScore(String text) {
		if (text == null) {
			return 0;
		}
		long exclamations = text.chars().filter(c -> c == '!').count();
		long words = AGITATED_WORDS.stream().filter(text::contains).count();
		return (int) (Math.min(exclamations, 2) * 10 + Math.min(words, 2) * 15);
	}
}
