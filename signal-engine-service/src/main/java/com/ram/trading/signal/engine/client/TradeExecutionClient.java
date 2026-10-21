
package com.ram.trading.signal.engine.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class TradeExecutionClient {

    private final WebClient webClient;

    public TradeExecutionClient(
            WebClient.Builder webClientBuilder,
            @Value("${trade.execution.service.url}") String baseUrl) {

        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Submit a new entry to Trade Execution Service.
     */
    public Mono<TradeExecutionResponse> executeTrade(
            TradeExecutionRequest request) {

        return webClient.post()
                .uri("/api/v1/trade-execution")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TradeExecutionResponse.class);
    }

    /**
     * Read-only retrieval of persisted OPEN positions owned by the
     * Trade Execution Service. This does not submit orders or imply readiness.
     */
    public Mono<List<TradeExecutionPosition>> getOpenTrades() {
        return webClient.get()
                .uri("/api/v1/trade-execution/open")
                .retrieve()
                .bodyToFlux(TradeExecutionPosition.class)
                .collectList();
    }

    /**
     * Persist a Signal Engine-owned protection update for an OPEN execution position.
     * This endpoint updates internal trigger values only; it does not place broker-side stops.
     */
    public Mono<TradeExecutionPosition> updateProtection(
            TradeProtectionUpdateRequest request) {

        if (request == null || request.signalId() == null
                || (request.stopLoss() == null && request.targetPrice() == null)) {
            return Mono.error(new IllegalArgumentException(
                    "signalId and at least one protection value are required"));
        }
        if (Boolean.TRUE.equals(request.targetExtended()) && request.targetPrice() == null) {
            return Mono.error(new IllegalArgumentException(
                    "targetPrice is required when marking a target extension"));
        }

        return webClient.patch()
                .uri("/api/v1/trade-execution/protection")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TradeExecutionPosition.class);
    }

    /**
     * Request an exit for an existing executed position.
     * Do not automatically retry after a timeout; the broker may have accepted
     * the order even if the response was not received.
     */
    public Mono<TradeExecutionResponse> exitTrade(
            TradeExitRequest request) {

        if (request == null || request.signalId() == null) {
            return Mono.error(
                    new IllegalArgumentException(
                            "signalId is required for an exit"));
        }

        if (request.quantity() == null || request.quantity() <= 0) {
            return Mono.error(
                    new IllegalArgumentException(
                            "Exit quantity must be greater than zero"));
        }

        return webClient.post()
                .uri("/api/v1/trade-execution/exit")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TradeExecutionResponse.class);
    }

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

    /**
     * Read model matching the existing ExecutedTradeRecord JSON contract.
     * Fields are deliberately read-only; the Signal Engine must not mutate
     * Trade Execution persistence directly.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TradeExecutionPosition(
            Long id,
            Long signalId,
            String symbol,
            String instrumentKey,
            String side,
            Integer requestedQuantity,
            Integer filledQuantity,
            BigDecimal averageEntryPrice,
            BigDecimal initialStopLoss,
            BigDecimal currentStopLoss,
            BigDecimal targetPrice,
            String status,
            String entryOrderId,
            String exitOrderId,
            Integer exitOrderQuantity,
            Integer exitFilledQuantity,
            Integer remainingQuantity,
            BigDecimal exitAveragePrice,
            BigDecimal exitPrice,
            BigDecimal realizedPnl,
            String exitReason,
            LocalDateTime openedAt,
            LocalDateTime closedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Boolean targetExtended) {
    }

    /**
     * Must match Trade Execution Service's TradeExitRequest fields.
     */
    public record TradeExitRequest(
            Long signalId,
            Integer quantity,
            String orderType) {
    }

    public record TradeProtectionUpdateRequest(
            Long signalId,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            Boolean targetExtended) {
    }

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
