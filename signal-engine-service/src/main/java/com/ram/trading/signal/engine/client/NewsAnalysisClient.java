package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.request.NewsAnalysisRequest;
import com.ram.trading.signal.engine.dto.response.NewsAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class NewsAnalysisClient {

    private final WebClient.Builder webClientBuilder;

    public Mono<NewsAnalysisResponse> analyze(
            NewsAnalysisRequest request) {

        return webClientBuilder.build()
                .post()
                .uri("http://localhost:8088/news/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(NewsAnalysisResponse.class);
    }
}