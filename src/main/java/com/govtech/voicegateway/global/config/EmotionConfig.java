package com.govtech.voicegateway.global.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.govtech.voicegateway.domain.emotion.EmotionAnalyzer;
import com.govtech.voicegateway.domain.emotion.EmotionProperties;
import com.govtech.voicegateway.domain.emotion.HttpEmotionAnalyzer;
import com.govtech.voicegateway.domain.emotion.MockEmotionAnalyzer;

import lombok.extern.slf4j.Slf4j;

/**
 * 감정 판정 API 주소(voicegateway.emotion.url)가 있으면 그 API를, 없으면 목업 분석기를 쓴다. API 호출이 실패해도 목업으로 대신한다.
 */
@Slf4j
@Configuration
public class EmotionConfig {

	@Bean
	EmotionAnalyzer emotionAnalyzer(EmotionProperties properties) {
		MockEmotionAnalyzer mock = new MockEmotionAnalyzer(properties.agitatedScore());
		if (properties.url() == null) {
			log.info("감정 분석: 목업 (격양 기준 {}점)", properties.agitatedScore());
			return mock;
		}
		log.info("감정 분석: {} (상태 경고·격앙이면 격양, 실패하면 목업)", properties.url());
		return new HttpEmotionAnalyzer(properties.url(),
				Duration.ofMillis(properties.timeoutMs()), mock);
	}
}
