package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.*;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.ai.TradingDecisionRequest;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
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

        HttpClient httpClient = HttpClient.create()
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        3000)
                .responseTimeout(
                        Duration.ofSeconds(15))
                .doOnConnected(connection ->
                        connection
                                .addHandlerLast(
                                        new ReadTimeoutHandler(
                                                15,
                                                TimeUnit.SECONDS))
                                .addHandlerLast(
                                        new WriteTimeoutHandler(
                                                15,
                                                TimeUnit.SECONDS)));

        this.client = builder
                .clientConnector(
                        new ReactorClientHttpConnector(
                                httpClient))
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

        String symbol =
                request.getSignalRequest() != null
                        ? request.getSignalRequest().getSymbol()
                        : "UNKNOWN";

        long start =
                System.currentTimeMillis();

        log.info(
                "Calling AI Decision Service for {}",
                symbol);

        return client
                .post()
                .uri("/ai/trading-decision")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiDecisionResponse.class)

                .doOnSuccess(response -> {

                    long elapsed =
                            System.currentTimeMillis()
                                    - start;

                    log.info(
                            "AI Decision Received for {} in {} ms : Decision={}, Confidence={}",
                            symbol,
                            elapsed,
                            response.getDecision(),
                            response.getDecision() != null
                                    ? response.getDecision().getConfidence()
                                    : null);
                })

                .doOnError(error -> {

                    long elapsed =
                            System.currentTimeMillis()
                                    - start;

                    log.error(
                            "AI Decision Failed for {} after {} ms",
                            symbol,
                            elapsed,
                            error);
                });
    }
}