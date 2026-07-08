package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.request.NewsAnalysisRequest;
import com.ram.trading.signal.engine.dto.response.NewsAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class NewsAnalysisClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${news.service.base-url}")
    private String baseUrl;

    public Mono<NewsAnalysisResponse> analyze(
            NewsAnalysisRequest request) {

        return webClientBuilder.build()
                .post()
                .uri(baseUrl + "/news/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(NewsAnalysisResponse.class);
    }
}