package com.ram.trading.market.data.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class BrokerAuthClientImpl
        implements BrokerAuthClient {

    private final WebClient webClient;

    @Value("${broker.auth.base-url}")
    private String brokerBaseUrl;

    @Override
    public String getAccessToken() {

        return webClient
                .get()
                .uri(brokerBaseUrl + "/upstox/auth/token")
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}