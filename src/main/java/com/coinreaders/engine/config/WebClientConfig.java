package com.coinreaders.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        // WebClient.Builder를 주입받아 기본 WebClient 빈을 생성
        // Upbit API base URL을 설정하여 URI 빌딩 문제 해결
        return builder
            .baseUrl("https://api.upbit.com")
            .build();
    }
}