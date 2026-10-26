package com.govtech.voicegateway.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 브라우저 테스트 콘솔(/test). {@code voicegateway.test-console.enabled=false}면 콘솔과 현황·STT 기록 API를 모두 끈다.
 * 정적 파일을 기본 static/ 대신 test-console/에 두어, 끄면 파일도 나가지 않게 한다.
 */
@Configuration
@ConditionalOnBooleanProperty(name = "voicegateway.test-console.enabled", matchIfMissing = true)
public class TestConsoleConfig implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/test/**").addResourceLocations("classpath:/test-console/");
	}

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addRedirectViewController("/test", "/test/index.html");
		registry.addRedirectViewController("/test/", "/test/index.html");
	}
}
