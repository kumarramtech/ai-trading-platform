package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.SignalExplanationRequest;
import com.ram.trading.signal.engine.dto.SignalExplanationResponse;
import com.ram.trading.signal.engine.dto.TradeReviewRequest;
import com.ram.trading.signal.engine.dto.TradeReviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
}