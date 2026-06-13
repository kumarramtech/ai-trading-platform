package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AIServiceClient {

    private final WebClient.Builder webClientBuilder;

    public Mono<SignalExplanationResponse> explainSignal(
            SignalExplanationRequest request) {

        return webClientBuilder.build()
                .post()
                .uri("http://localhost:8086/ai/explain-signal")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SignalExplanationResponse.class);
    }

    public Mono<TradeReviewResponse> reviewTrade(
            TradeReviewRequest request) {

        return webClientBuilder.build()
                .post()
                .uri("http://localhost:8086/ai/review-trade")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TradeReviewResponse.class);
    }

    public Mono<StrategyReviewResponse> reviewStrategy(
            List<TradeReviewRequest> request) {

        return webClientBuilder.build()
                .post()
                .uri("http://localhost:8086/ai/strategy-review")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(StrategyReviewResponse.class);
    }

    public Mono<RiskAnalysisResponse> analyzeRisk(
            RiskAnalysisRequest request) {

        return webClientBuilder.build()
                .post()
                .uri("http://localhost:8086/ai/risk-analysis")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RiskAnalysisResponse.class);
    }
}