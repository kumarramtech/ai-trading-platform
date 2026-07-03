package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.config.WebClientConfig;
import com.ram.trading.signal.engine.dto.response.MarketSentimentResponse;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class MarketSentimentClient {

    private final WebClient.Builder builder;

    public Mono<MarketSentimentResponse>
            getSentiment(String symbol) {

        return builder.build().get()
                .uri("http://localhost:8086/market/sentiment/{symbol}",
                        symbol)
                .retrieve()
                .bodyToMono(MarketSentimentResponse.class);
    }
}