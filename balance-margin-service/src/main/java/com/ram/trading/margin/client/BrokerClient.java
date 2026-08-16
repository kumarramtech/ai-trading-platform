package com.ram.trading.margin.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class BrokerClient {

    private final WebClient webClient;

    public Mono<String> getAccessToken() {

        return webClient.get()
                .uri("/upstox/auth/token")
                .retrieve()
                .bodyToMono(String.class);
    }
}