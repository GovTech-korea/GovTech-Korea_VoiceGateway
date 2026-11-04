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
 * 감정 모델 주소(voicegateway.emotion.url)가 있으면 그 모델을, 없으면 목업 분석기를 쓴다.
 */
@Slf4j
@Configuration
public class EmotionConfig {

	@Bean
	EmotionAnalyzer emotionAnalyzer(EmotionProperties properties) {
		if (properties.url() == null) {
			log.info("감정 분석: 목업 (격양 기준 {}점)", properties.agitatedScore());
			return new MockEmotionAnalyzer(properties.agitatedScore());
		}
		log.info("감정 분석: {} (격양 기준 {}점)", properties.url(), properties.agitatedScore());
		return new HttpEmotionAnalyzer(properties.url(), properties.agitatedScore(),
				Duration.ofMillis(properties.timeoutMs()));
	}
}
