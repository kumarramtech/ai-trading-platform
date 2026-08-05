package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.response.MarketSentimentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class MarketSentimentClient {

    private final WebClient client;

    public MarketSentimentClient(
            WebClient.Builder builder,
            @Value("${market.sentiment.service.url}") String marketSentimentUrl) {

        this.client = builder
                .baseUrl(marketSentimentUrl)
                .build();
    }

    public Mono<MarketSentimentResponse> getSentiment(
            String symbol) {

        return client
                .get()
                .uri("/market/sentiment/{symbol}", symbol)
                .retrieve()
                .bodyToMono(MarketSentimentResponse.class);
    }
}