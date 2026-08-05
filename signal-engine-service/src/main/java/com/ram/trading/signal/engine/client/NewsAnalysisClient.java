package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.request.NewsAnalysisRequest;
import com.ram.trading.signal.engine.dto.response.NewsAnalysisResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class NewsAnalysisClient {

    private final WebClient client;

    public NewsAnalysisClient(
            WebClient.Builder builder,
            @Value("${news.service.base-url}") String baseUrl) {

        this.client = builder
                .baseUrl(baseUrl)
                .build();
    }

    public Mono<NewsAnalysisResponse> analyze(
            NewsAnalysisRequest request) {

        return client
                .post()
                .uri("/news/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(NewsAnalysisResponse.class);
    }
}