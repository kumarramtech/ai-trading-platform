package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class StockServiceClient implements MarketDataProvider {

    private final WebClient.Builder webClientBuilder;
    @Value("${stock.service.url}")
    private String stockServiceUrl;

    @Override
    public Mono<StockResponse> getStockPrice(String symbol) {

        return webClientBuilder
                .baseUrl(stockServiceUrl)
                .build()
                .get()
                .uri("/stocks/{symbol}", symbol)
                .retrieve()
                .bodyToMono(StockResponse.class);
    }

    public Flux<StockResponse> getAllStocks() {

        return webClientBuilder
                .baseUrl(stockServiceUrl)
                .build()
                .get()
                .uri("/stocks/allstocks")
                .retrieve()
                .bodyToFlux(StockResponse.class);
    }

    public Mono<HistoricalCandleResponse> getHistoricalCandles(
            String symbol,
            String interval,
            LocalDate from,
            LocalDate to){

        return webClientBuilder.baseUrl(stockServiceUrl).build().get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/stocks/{symbol}/history")
                        .queryParam("interval", interval)
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build(symbol))
                .retrieve()
                .bodyToMono(HistoricalCandleResponse.class);
    }
}