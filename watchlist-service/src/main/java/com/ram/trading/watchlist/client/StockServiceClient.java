package com.ram.trading.watchlist.client;

import com.ram.trading.watchlist.dto.TradableInstrumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
public class StockServiceClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${stock.service.base-url}")
    private String stockServiceBaseUrl;

    public Flux<TradableInstrumentResponse> getTradableEquities() {

        return webClientBuilder
                .baseUrl(stockServiceBaseUrl)
                .build()
                .get()
                .uri("/api/v1/instruments/tradable-equities")
                .retrieve()
                .bodyToFlux(TradableInstrumentResponse.class);
    }
}