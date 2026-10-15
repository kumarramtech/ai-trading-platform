package com.ram.trading.signal.engine.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * HTTP client for the Trade Execution Service.
 *
 * This client only transports the request and response. The caller must enforce
 * Signal Service execution-mode rules, and Trade Execution Service must continue
 * enforcing its own execution.live-enabled safety guard.
 */
@Component
public class TradeExecutionClient {

    private final WebClient webClient;

    public TradeExecutionClient(
            WebClient.Builder webClientBuilder,
            @Value("${trade.execution.service.url}") String baseUrl) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    public Mono<TradeExecutionResponse> executeTrade(TradeExecutionRequest request) {
        return webClient
                .post()
                .uri("/api/v1/trade-execution")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TradeExecutionResponse.class);
    }

    /** Request contract matching Trade Execution Service's DTO. */
    public record TradeExecutionRequest(
            Long signalId,
            String symbol,
            String instrumentKey,
            String signal,
            BigDecimal entryPrice,
            Integer quantity,
            BigDecimal requiredMargin,
            BigDecimal stopLoss,
            BigDecimal target,
            String orderType) {
    }

    /** Response contract matching Trade Execution Service's DTO. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TradeExecutionResponse(
            boolean success,
            Long signalId,
            String symbol,
            String signal,
            Integer quantity,
            BigDecimal executedPrice,
            BigDecimal requiredMargin,
            String orderId,
            String status,
            String message,
            Integer filledQuantity,
            String rejectionReason) {
    }
}
