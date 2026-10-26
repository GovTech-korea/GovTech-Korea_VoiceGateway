package com.govtech.voicegateway.global.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 벽시계 시각. 테스트에서 시각을 고정·진행할 수 있도록 빈으로 둔다.
 */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemDefaultZone();
	}
}
