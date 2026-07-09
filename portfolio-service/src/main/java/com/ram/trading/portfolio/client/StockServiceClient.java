package com.ram.trading.portfolio.client;

import com.ram.trading.portfolio.dto.StockResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class StockServiceClient {

    private final WebClient webClient;

    public Mono<StockResponse> getStockPrice(
            String symbol) {

        return webClient.get()
                .uri("http://localhost:8081/stocks/{symbol}",
                        symbol)
                .retrieve()
                .bodyToMono(StockResponse.class);
    }

}