package com.govtech.voicegateway.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 시연용 운영 상담사 웹(/ops): 상담 화면과 통화 보호정책 설정. 테스트 콘솔(/test)과 달리 끄는 설정이 없다.
 */
@Configuration
public class OpsConsoleConfig implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/ops/**").addResourceLocations("classpath:/ops-console/");
	}

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addRedirectViewController("/ops", "/ops/index.html");
		registry.addRedirectViewController("/ops/", "/ops/index.html");
	}
}
