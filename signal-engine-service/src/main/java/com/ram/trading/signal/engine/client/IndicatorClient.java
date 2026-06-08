package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class IndicatorClient {

    private final WebClient.Builder webClientBuilder;

    public Mono<TechnicalIndicatorResponse>
    getLatest(String symbol) {

        return webClientBuilder.build()
                .get()
                .uri(
                        "http://localhost:8085/backtest/latest/{symbol}",
                        symbol)
                .retrieve()
                .bodyToMono(
                        TechnicalIndicatorResponse.class);
    }
}