package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.ai.NewsArticle;
import com.ram.trading.signal.engine.dto.request.NewsAnalysisRequest;
import com.ram.trading.signal.engine.dto.response.NewsAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Slf4j
public class NewsAnalysisClient {

    private final WebClient client;

    public NewsAnalysisClient(
            WebClient.Builder builder,
            @Value("${news.service.base-url}") String baseUrl) {

        this.client = builder
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Legacy news-analysis call.
     * Keep temporarily until the new raw-news flow is verified.
     */
    public Mono<NewsAnalysisResponse> analyze(
            NewsAnalysisRequest request) {

        return client
                .post()
                .uri("/news/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(NewsAnalysisResponse.class);
    }

    /**
     * Fetch raw/latest news from News Service.
     * No LLM call is involved here.
     */
    public Mono<List<NewsArticle>> getLatestNews(String symbol) {

        return client
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/news/latest")
                        .queryParam("symbol", symbol)
                        .build())
                .retrieve()
                .bodyToMono(
                        new ParameterizedTypeReference<List<NewsArticle>>() {}
                )
                .onErrorResume(ex -> {

                    log.warn(
                            "News Service unavailable for [{}]. Continuing without news.",
                            symbol,
                            ex
                    );

                    return Mono.just(List.of());
                });
    }
}