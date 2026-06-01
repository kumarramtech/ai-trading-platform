package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.StockResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class StockServiceClient {

    private final WebClient webClient;

    public Mono<StockResponse> getStockPrice() {

        return webClient
                .get()
                .uri("http://localhost:8081/stocks/TCS")
                .retrieve()
                .bodyToMono(StockResponse.class);
    }
}