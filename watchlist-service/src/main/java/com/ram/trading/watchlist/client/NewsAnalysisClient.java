package com.ram.trading.watchlist.client;

import com.ram.trading.watchlist.dto.MarketTrendAnalysisResponse;
import com.ram.trading.watchlist.dto.SectorStockAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class NewsAnalysisClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${news.analysis.service.base-url}")
    private String newsAnalysisServiceBaseUrl;

    public Mono<MarketTrendAnalysisResponse> getMarketTrend() {

        return webClientBuilder
                .baseUrl(newsAnalysisServiceBaseUrl)
                .build()
                .get()
                .uri("/news/market-trend")
                .retrieve()
                .bodyToMono(MarketTrendAnalysisResponse.class);
    }

    public Mono<SectorStockAnalysisResponse> getMarketTrendStocks() {

        return webClientBuilder
                .baseUrl(newsAnalysisServiceBaseUrl)
                .build()
                .get()
                .uri("/news/market-trend/stocks")
                .retrieve()
                .bodyToMono(SectorStockAnalysisResponse.class);
    }
}