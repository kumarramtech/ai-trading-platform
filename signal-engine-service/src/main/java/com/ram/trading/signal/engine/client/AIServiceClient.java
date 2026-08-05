package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.*;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.ai.TradingDecisionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Slf4j
public class AIServiceClient {

    private final WebClient client;

    public AIServiceClient(
            WebClient.Builder builder,
            @Value("${ai.service.url}") String aiServiceUrl) {

        this.client = builder
                .baseUrl(aiServiceUrl)
                .build();
    }


    public Mono<SignalExplanationResponse> explainSignal(
            SignalExplanationRequest request) {

        return client
                .post()
                .uri("/ai/explain-signal")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SignalExplanationResponse.class);
    }

    public Mono<TradeReviewResponse> reviewTrade(
            TradeReviewRequest request) {

        return client
                .post()
                .uri("/ai/review-trade")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TradeReviewResponse.class);
    }

    public Mono<StrategyReviewResponse> reviewStrategy(
            List<TradeReviewRequest> request) {

        return client
                .post()
                .uri("/ai/strategy-review")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(StrategyReviewResponse.class);
    }

    public Mono<RiskAnalysisResponse> analyzeRisk(
            RiskAnalysisRequest request) {

        return client
                .post()
                .uri("/ai/risk-analysis")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RiskAnalysisResponse.class);
    }

    public Mono<AiDecisionResponse> evaluate(
            TradingDecisionRequest request) {

        String symbol = request.getSignalRequest() != null
                ? request.getSignalRequest().getSymbol()
                : "UNKNOWN";

        log.info("Preparing AI Decision Request for {}", symbol);

        return client
                .post()
                .uri("/ai/trading-decision")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiDecisionResponse.class)
                .doOnSubscribe(subscription ->
                        log.info("Calling AI Decision Service for {}", symbol))
                .doOnSuccess(response ->
                        log.info("AI Decision Received for {} : Decision={}, Confidence={}",
                                symbol,
                                response.getDecision(),
                                response.getDecision().getConfidence()))
                .doOnError(error ->
                        log.error("AI Decision Failed for {}", symbol, error));
    }
}