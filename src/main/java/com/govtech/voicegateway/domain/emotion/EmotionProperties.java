package com.govtech.voicegateway.domain.emotion;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 감정 분석 설정 (application.yaml의 voicegateway.emotion.*).
 *
 * @param url           STT 서버의 감정 판정 API 주소 (예: http://127.0.0.1:8100/v1/acoustic-arousal). 없으면 목업 분석기({@link MockEmotionAnalyzer})를 쓴다
 * @param agitatedScore 이 점수 이상이면 격양으로 본다
 * @param timeoutMs     감정 판정 API 응답을 기다리는 시간
 */
@ConfigurationProperties("voicegateway.emotion")
public record EmotionProperties(
		URI url,
		@DefaultValue("70") int agitatedScore,
		@DefaultValue("3000") long timeoutMs) {
}
