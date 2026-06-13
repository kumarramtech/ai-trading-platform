package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class StockServiceClient implements MarketDataProvider {

    private final WebClient webClient;
    @Override
    public Mono<StockResponse> getStockPrice(String symbol) {

        return webClient
                .get()
                .uri("http://localhost:8081/stocks/{symbol}",symbol)
                .retrieve()
                .bodyToMono(StockResponse.class);
    }

    public Flux<StockResponse> getAllStocks() {

        return webClient
                .get()
                .uri("http://localhost:8081/stocks/allstocks")
                .retrieve()
                .bodyToFlux(StockResponse.class);
    }
}