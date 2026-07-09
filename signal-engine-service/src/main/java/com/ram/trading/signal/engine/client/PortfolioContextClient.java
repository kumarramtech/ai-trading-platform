package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.portfolio.PortfolioContextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class PortfolioContextClient {

    private final WebClient.Builder webClientBuilder;
    @Value("${portfolio.service.url}")
    private String portfolioUrl;

    public Mono<PortfolioContextResponse> getPortfolioContext() {
        return webClientBuilder
                .baseUrl(portfolioUrl)
                .build()
                .get()
                .uri("/portfolio/context")
                .retrieve()
                .bodyToMono(PortfolioContextResponse.class);
    }

}