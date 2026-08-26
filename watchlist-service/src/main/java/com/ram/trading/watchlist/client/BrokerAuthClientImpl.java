package com.ram.trading.watchlist.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BrokerAuthClientImpl implements BrokerAuthClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${broker.auth.base-url}")
    private String brokerBaseUrl;

    @Override
    public Mono<String> getAccessToken() {

        return webClientBuilder
                .baseUrl(brokerBaseUrl)
                .build()
                .get()
                .uri("/upstox/auth/token")
                .retrieve()
                .bodyToMono(String.class);
    }
}