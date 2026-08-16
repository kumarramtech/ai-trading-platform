package com.ram.trading.margin.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(
            WebClient.Builder builder,
            @Value("${broker.service.base-url}") String brokerServiceUrl) {

        return builder
                .baseUrl(brokerServiceUrl)
                .build();
    }
}