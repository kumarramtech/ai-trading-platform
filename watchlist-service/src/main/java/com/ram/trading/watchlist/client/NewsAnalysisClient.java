package com.ram.trading.watchlist.client;

import com.ram.trading.watchlist.dto.MarketTrendAnalysisResponse;
import com.ram.trading.watchlist.dto.SectorStockAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class NewsAnalysisClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${news.analysis.service.base-url}")
    private String newsAnalysisServiceBaseUrl;

    /**
     * News analysis answers after one or two AI calls (~40s each at worst), so
     * the bound sits below its 180s async timeout but well above a normal call.
     */
    @Value("${news.analysis.service.timeout-seconds:150}")
    private long timeoutSeconds = 150;

    public Mono<MarketTrendAnalysisResponse> getMarketTrend() {

        return webClientBuilder
                .baseUrl(newsAnalysisServiceBaseUrl)
                .build()
                .get()
                .uri("/news/market-trend")
                .retrieve()
                .bodyToMono(MarketTrendAnalysisResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds));
    }

    public Mono<SectorStockAnalysisResponse> getMarketTrendStocks() {

        return webClientBuilder
                .baseUrl(newsAnalysisServiceBaseUrl)
                .build()
                .get()
                .uri("/news/market-trend/stocks")
                .retrieve()
                .bodyToMono(SectorStockAnalysisResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds));
    }
}
