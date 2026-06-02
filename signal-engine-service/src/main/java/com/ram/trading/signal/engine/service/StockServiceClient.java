package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.db.TradingSignalRepository;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockServiceClient {

    private final WebClient webClient;

    public Mono<StockResponse> getStockPrice(String symbol) {

        return webClient
                .get()
                .uri("http://localhost:8081/stocks/"+symbol)
                .retrieve()
                .bodyToMono(StockResponse.class);
    }
}