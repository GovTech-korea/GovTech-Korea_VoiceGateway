package com.govtech.voicegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VoiceGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(VoiceGatewayApplication.class, args);
	}

}
