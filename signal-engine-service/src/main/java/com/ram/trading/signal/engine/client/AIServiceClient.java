package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.*;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.ai.TradingDecisionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AIServiceClient {

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    private final WebClient.Builder builder;

    private WebClient client() {
        return builder.baseUrl(aiServiceUrl).build();
    }

    public Mono<SignalExplanationResponse> explainSignal(
            SignalExplanationRequest request) {

        return client()
                .post()
                .uri("/ai/explain-signal")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SignalExplanationResponse.class);
    }

    public Mono<TradeReviewResponse> reviewTrade(
            TradeReviewRequest request) {

        return client()
                .post()
                .uri("/ai/review-trade")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TradeReviewResponse.class);
    }

    public Mono<StrategyReviewResponse> reviewStrategy(
            List<TradeReviewRequest> request) {

        return  client()
                .post()
                .uri("/ai/strategy-review")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(StrategyReviewResponse.class);
    }

    public Mono<RiskAnalysisResponse> analyzeRisk(
            RiskAnalysisRequest request) {

        return client()
                .post()
                .uri("/ai/risk-analysis")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RiskAnalysisResponse.class);
    }

    public AiDecisionResponse evaluate(
            TradingDecisionRequest request) {

        return client().post()

                .uri("/ai/trading-decision")

                .bodyValue(request)

                .retrieve()

                .bodyToMono(AiDecisionResponse.class)

                .block();

    }
}