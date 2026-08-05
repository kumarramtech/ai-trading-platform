package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.portfolio.PortfolioContextResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class PortfolioContextClient {

    private final WebClient client;

    public PortfolioContextClient(
            WebClient.Builder builder,
            @Value("${portfolio.service.url}") String portfolioUrl) {

        this.client = builder
                .baseUrl(portfolioUrl)
                .build();
    }

    public Mono<PortfolioContextResponse> getPortfolioContext() {

        return client
                .get()
                .uri("/portfolio/context")
                .retrieve()
                .bodyToMono(PortfolioContextResponse.class);
    }
}