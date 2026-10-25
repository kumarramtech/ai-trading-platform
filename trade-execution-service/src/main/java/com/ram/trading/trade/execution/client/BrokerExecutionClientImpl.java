package com.ram.trading.trade.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.ram.trading.trade.execution.dto.BrokerOrderRequest;
import com.ram.trading.trade.execution.dto.BrokerOrderResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderDetailsResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderHistoryResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderRequest;
import com.ram.trading.trade.execution.dto.UpstoxOrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BrokerExecutionClientImpl implements BrokerExecutionClient {

    private final WebClient.Builder webClientBuilder;
    private final BrokerClient brokerClient;

    @Value("${upstox.order-url}")
    private String upstoxOrderUrl;

    @Value("${upstox.order-modify-url:https://api-hft.upstox.com/v2/order/modify}")
    private String upstoxOrderModifyUrl;

    @Value("${upstox.order-cancel-url:https://api-hft.upstox.com/v2/order/cancel}")
    private String upstoxOrderCancelUrl;

    @Value("${upstox.order-details-url:https://api.upstox.com/v2/order/details}")
    private String upstoxOrderDetailsUrl;

    @Value("${upstox.order-history-url:https://api.upstox.com/v2/order/history}")
    private String upstoxOrderHistoryUrl;

    @Override
    public Mono<BrokerOrderResponse> executeOrder(BrokerOrderRequest request) {

        if (request == null) {
            return Mono.just(BrokerOrderResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message("Broker order request must not be null")
                    .build());
        }

        String orderType = normalizeOrderType(request.getOrderType());

        String validationError = validateOrderRequest(request, orderType);

        if (validationError != null) {
            log.warn(
                    "Invalid broker order request | Symbol={} | Reason={}",
                    request.getSymbol(),
                    validationError);

            return Mono.just(BrokerOrderResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message(validationError)
                    .build());
        }

        String tag = request.getTag();

        log.info("========================================");
        log.info("UPSTOX ORDER EXECUTION STARTED");
        log.info(
                "Symbol={} | InstrumentKey={} | Signal={} | TransactionType={} "
                        + "| Quantity={} | ReferencePrice={} | OrderType={} | Tag={}",
                request.getSymbol(),
                request.getInstrumentKey(),
                request.getSignal(),
                request.getTransactionType(),
                request.getQuantity(),
                request.getPrice(),
                orderType,
                tag);

        final UpstoxOrderRequest upstoxOrderRequest;

        try {
            upstoxOrderRequest =
                    buildUpstoxOrderRequest(request, orderType, tag);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to build Upstox order request | Symbol={} | Tag={}",
                    request.getSymbol(),
                    tag,
                    exception);

            return Mono.just(BrokerOrderResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message(exception.getMessage() != null
                            ? exception.getMessage()
                            : "Unable to build Upstox order request")
                    .build());
        }

        return brokerClient.getAccessToken()
                .switchIfEmpty(Mono.error(
                        new IllegalStateException(
                                "Upstox access token is unavailable")))
                // Errors before the order request is sent are definitive: no order exists.
                .onErrorMap(AccessTokenUnavailableException::new)
                .flatMap(accessToken -> webClientBuilder.build()
                        .post()
                        .uri(upstoxOrderUrl)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(upstoxOrderRequest)
                        .retrieve()
                        .bodyToMono(UpstoxOrderResponse.class)
                        .switchIfEmpty(Mono.error(
                                new IllegalStateException(
                                        "Empty response received from Upstox")))
                        .map(response ->
                                buildBrokerOrderResponse(request, response)))
                .onErrorResume(error -> {
                    if (error instanceof AccessTokenUnavailableException) {
                        log.error("Upstox order NOT sent: access token unavailable | Symbol={} | Tag={}",
                                request.getSymbol(), tag, error);
                        return Mono.just(BrokerOrderResponse.builder()
                                .success(false)
                                .status("REJECTED")
                                .message("Order was not sent because the Upstox access token is unavailable: "
                                        + error.getMessage())
                                .build());
                    }

                    log.error(
                            "Upstox order placement error; reconciling before "
                                    + "any retry | Symbol={} | Tag={}",
                            request.getSymbol(),
                            tag,
                            error);

                    return reconcileAfterPlacementError(
                            request,
                            tag,
                            error);
                });
    }

    @Override
    public Mono<BrokerOrderResponse> modifyOrder(String orderId, BrokerOrderRequest request) {
        if (orderId == null || orderId.isBlank()) {
            return Mono.just(BrokerOrderResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message("orderId must not be blank")
                    .build());
        }
        if (request == null) {
            return Mono.just(BrokerOrderResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message("Broker order modification request must not be null")
                    .build());
        }

        String orderType = normalizeOrderType(request.getOrderType());
        String validationError = validateOrderRequest(request, orderType);
        if (validationError != null) {
            return Mono.just(BrokerOrderResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message(validationError)
                    .build());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", orderId.trim());
        body.put("quantity", request.getQuantity());
        body.put("validity", request.getValidity() == null || request.getValidity().isBlank()
                ? "DAY" : request.getValidity().trim().toUpperCase());
        body.put("price", "MARKET".equals(orderType) || "SL-M".equals(orderType)
                ? BigDecimal.ZERO : request.getPrice());
        body.put("order_type", orderType);
        body.put("disclosed_quantity", 0);
        body.put("trigger_price", request.getTriggerPrice() == null
                ? BigDecimal.ZERO : request.getTriggerPrice());

        return brokerClient.getAccessToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Upstox access token is unavailable for order modification")))
                .flatMap(accessToken -> webClientBuilder.build()
                        .put()
                        .uri(upstoxOrderModifyUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "Empty Upstox order modification response for order " + orderId)))
                        .map(response -> {
                            String status = response.path("status").asText("");
                            String returnedId = response.path("data").path("order_id").asText("");
                            if (!"success".equalsIgnoreCase(status) || returnedId.isBlank()
                                    || !orderId.trim().equals(returnedId)) {
                                return BrokerOrderResponse.builder()
                                        .success(false)
                                        .orderId(returnedId.isBlank() ? orderId.trim() : returnedId)
                                        .status("MODIFY_NOT_CONFIRMED")
                                        .message("Upstox did not confirm modification for order " + orderId)
                                        .build();
                            }
                            log.info("UPSTOX ORDER MODIFICATION ACKNOWLEDGED | OrderId={} | Type={} | Qty={} | Trigger={}",
                                    returnedId, orderType, request.getQuantity(), request.getTriggerPrice());
                            return BrokerOrderResponse.builder()
                                    .success(true)
                                    .orderId(returnedId)
                                    .status("MODIFY_ACKNOWLEDGED")
                                    .message("Broker acknowledged order modification; verify order details before relying on it")
                                    .build();
                        }));
    }

    @Override
    public Mono<UpstoxOrderDetailsResponse.DataResponse> getOrderDetails(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Mono.error(
                    new IllegalArgumentException("orderId must not be blank")
            );
        }

        return brokerClient.getAccessToken()
                .switchIfEmpty(Mono.error(
                        new IllegalStateException("Access token unavailable")
                ))
                .flatMap(accessToken -> webClientBuilder.build()
                        .get()
                        .uri(UriComponentsBuilder
                                .fromUriString(upstoxOrderDetailsUrl)
                                .queryParam("order_id", orderId)
                                .build()
                                .encode()
                                .toUri())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + accessToken
                        )
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(UpstoxOrderDetailsResponse.class)
                        .switchIfEmpty(Mono.error(
                                new IllegalStateException(
                                        "Empty order details response for order " + orderId
                                )
                        ))
                        .flatMap(response -> {
                            if (response.getData() == null) {
                                return Mono.error(
                                        new IllegalStateException(
                                                "Missing order details for order " + orderId
                                        )
                                );
                            }

                            if (!"success".equalsIgnoreCase(response.getStatus())) {
                                return Mono.error(
                                        new IllegalStateException(
                                                "Unsuccessful order details response for order "
                                                        + orderId
                                        )
                                );
                            }

                            return Mono.just(response.getData());
                        }));
    }

    /**
     * Searches a successful, validated broker order-history response for the exact tag.
     *
     * Contract:
     * - emits one matching history record when found;
     * - completes empty only when the broker response is successful and valid but
     *   contains no exact tag match;
     * - emits an error for authentication, transport, empty-response, malformed-data,
     *   or unsuccessful broker-response cases.
     *
     * Callers must not interpret an error as "order not found" and must never use an
     * inconclusive lookup as permission to submit a replacement order.
     */
    @Override
    public Mono<UpstoxOrderHistoryResponse.OrderData> findOrderByTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return Mono.error(
                    new IllegalArgumentException("Order tag must not be blank")
            );
        }

        return brokerClient.getAccessToken()
                .switchIfEmpty(Mono.error(
                        new IllegalStateException(
                                "Access token unavailable for order-history lookup"
                        )
                ))
                .flatMap(accessToken -> webClientBuilder.build()
                        .get()
                        .uri(UriComponentsBuilder
                                .fromUriString(upstoxOrderHistoryUrl)
                                .queryParam("tag", tag)
                                .build()
                                .encode()
                                .toUri())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + accessToken
                        )
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(UpstoxOrderHistoryResponse.class)
                        .switchIfEmpty(Mono.error(
                                new IllegalStateException(
                                        "Empty response from Upstox order history for tag=" + tag
                                )
                        ))
                        .flatMap(response -> {
                            if (response.getData() == null) {
                                return Mono.error(
                                        new IllegalStateException(
                                                "Missing order-history data for tag=" + tag
                                        )
                                );
                            }

                            if (!"success".equalsIgnoreCase(response.getStatus())) {
                                return Mono.error(
                                        new IllegalStateException(
                                                "Unsuccessful Upstox order-history response for tag="
                                                        + tag
                                        )
                                );
                            }

                            return Mono.justOrEmpty(
                                    selectMatchingOrder(response.getData(), tag));
                        }));
    }

    /**
     * Selects a matching history snapshot without assuming that the broker's
     * response array is chronologically ordered. Multiple distinct order IDs
     * for one deterministic tag, or conflicting snapshots with no reliable
     * timestamp, are treated as ambiguous and fail closed.
     *
     * Package-private for deterministic unit testing.
     */
    static UpstoxOrderHistoryResponse.OrderData selectMatchingOrder(
            List<UpstoxOrderHistoryResponse.OrderData> history,
            String tag) {
        List<UpstoxOrderHistoryResponse.OrderData> matches = new ArrayList<>();
        if (history != null) {
            for (UpstoxOrderHistoryResponse.OrderData order : history) {
                if (order != null && tag.equals(order.getTag())) {
                    matches.add(order);
                }
            }
        }

        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }

        String orderId = matches.get(0).getOrderId();
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalStateException(
                    "Multiple matching broker history records have no usable order ID for tag=" + tag);
        }
        for (UpstoxOrderHistoryResponse.OrderData match : matches) {
            if (match.getOrderId() == null || !orderId.equals(match.getOrderId())) {
                throw new IllegalStateException(
                        "Multiple broker orders share deterministic tag=" + tag
                                + "; refusing to guess which order to reconcile");
            }
        }

        Instant latestInstant = null;
        UpstoxOrderHistoryResponse.OrderData latest = null;
        for (UpstoxOrderHistoryResponse.OrderData match : matches) {
            Instant timestamp = historyTimestamp(match);
            if (timestamp == null) {
                throw new IllegalStateException(
                        "Multiple history snapshots for orderId=" + orderId
                                + " lack a parseable timestamp; refusing to guess latest state");
            }
            if (latestInstant == null || timestamp.isAfter(latestInstant)) {
                latestInstant = timestamp;
                latest = match;
            } else if (timestamp.equals(latestInstant) && latest != null
                    && snapshotsConflict(latest, match)) {
                throw new IllegalStateException(
                        "Conflicting broker history snapshots share the latest timestamp for orderId="
                                + orderId);
            }
        }
        return latest;
    }

    private static boolean snapshotsConflict(
            UpstoxOrderHistoryResponse.OrderData first,
            UpstoxOrderHistoryResponse.OrderData second) {
        return !sameText(first.getStatus(), second.getStatus())
                || !java.util.Objects.equals(first.getFilledQuantity(), second.getFilledQuantity())
                || !java.util.Objects.equals(first.getPendingQuantity(), second.getPendingQuantity())
                || !sameDecimal(first.getAveragePrice(), second.getAveragePrice());
    }

    private static boolean sameText(String first, String second) {
        return first == null ? second == null
                : second != null && first.equalsIgnoreCase(second);
    }

    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
        return first == null ? second == null
                : second != null && first.compareTo(second) == 0;
    }

    private static Instant historyTimestamp(UpstoxOrderHistoryResponse.OrderData order) {
        Instant exchangeTimestamp = parseTimestamp(order.getExchangeTimestamp());
        return exchangeTimestamp != null
                ? exchangeTimestamp : parseTimestamp(order.getOrderTimestamp());
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String timestamp = value.trim();
        try {
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try the other documented ISO representations below.
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException ignored) {
            // Some broker payloads omit the UTC offset; compare those as local UTC values.
        }
        try {
            return LocalDateTime.parse(timestamp).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    @Override
    public Mono<Boolean> cancelOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Mono.error(new IllegalArgumentException("orderId must not be blank"));
        }

        return brokerClient.getAccessToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Upstox access token is unavailable for order cancellation")))
                .flatMap(accessToken -> webClientBuilder.build()
                        .delete()
                        .uri(UriComponentsBuilder.fromUriString(upstoxOrderCancelUrl)
                                .queryParam("order_id", orderId)
                                .build().encode().toUri())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "Empty Upstox cancellation response for order " + orderId)))
                        .map(response -> {
                            String status = response.path("status").asText("");
                            String returnedId = response.path("data").path("order_id").asText("");
                            if (!"success".equalsIgnoreCase(status)
                                    || (!returnedId.isBlank() && !orderId.equals(returnedId))) {
                                throw new IllegalStateException(
                                        "Upstox did not confirm cancellation for order " + orderId);
                            }
                            log.warn("UPSTOX CANCELLATION ACKNOWLEDGED | OrderId={} | Verify terminal status next",
                                    orderId);
                            return true;
                        }));
    }

    private UpstoxOrderRequest buildUpstoxOrderRequest(
            BrokerOrderRequest request,
            String orderType,
            String tag) {

        BigDecimal orderPrice = "MARKET".equals(orderType)
                ? BigDecimal.ZERO
                : request.getPrice();

        BigDecimal triggerPrice = request.getTriggerPrice() != null
                ? request.getTriggerPrice()
                : BigDecimal.ZERO;

        String transactionType = request.getTransactionType() != null
                && !request.getTransactionType().isBlank()
                ? request.getTransactionType().trim().toUpperCase()
                : request.getSignal() != null
                  ? request.getSignal().trim().toUpperCase()
                  : null;

        String product = request.getProduct() != null
                && !request.getProduct().isBlank()
                ? request.getProduct()
                : "I";

        String validity = request.getValidity() != null
                && !request.getValidity().isBlank()
                ? request.getValidity()
                : "DAY";

        if (transactionType == null
                || (!"BUY".equals(transactionType)
                && !"SELL".equals(transactionType))) {
            throw new IllegalArgumentException(
                    "Transaction type must be BUY or SELL");
        }

        return UpstoxOrderRequest.builder()
                .quantity(request.getQuantity())
                .product(product)
                .validity(validity)
                .price(orderPrice)
                .tag(tag)
                .instrumentToken(request.getInstrumentKey())
                .orderType(orderType)
                .transactionType(transactionType)
                .disclosedQuantity(0)
                .triggerPrice(triggerPrice)
                .isAmo(false)
                .slice(false)
                .build();
    }

    private BrokerOrderResponse buildBrokerOrderResponse(
            BrokerOrderRequest request,
            UpstoxOrderResponse response) {

        if (response == null) {
            return BrokerOrderResponse.builder()
                    .success(false)
                    .status("FAILED")
                    .message("Empty response received from Upstox")
                    .build();
        }

        if (!"success".equalsIgnoreCase(response.getStatus())) {
            return BrokerOrderResponse.builder()
                    .success(false)
                    .status("REJECTED")
                    .message("Upstox rejected the order")
                    .build();
        }

        String orderId = null;
        if (response.getData() != null
                && response.getData().getOrderIds() != null
                && !response.getData().getOrderIds().isEmpty()) {
            orderId = response.getData().getOrderIds().get(0);
        }

        if (orderId == null || orderId.isBlank()) {
            return BrokerOrderResponse.builder()
                    .success(false)
                    .status("FAILED")
                    .message("Upstox returned success without an order ID")
                    .build();
        }

        log.info("UPSTOX ORDER ACCEPTED | Symbol={} | OrderId={} | Tag={}",
                request.getSymbol(), orderId, request.getTag());

        return BrokerOrderResponse.builder()
                .success(true)
                .orderId(orderId)
                .status("ORDER_PLACED")
                .message("Order successfully placed with Upstox")
                .build();
    }

    private String normalizeOrderType(String orderType) {
        if (orderType == null || orderType.isBlank()) {
            return "MARKET";
        }
        return orderType.trim().toUpperCase();
    }

    private String validateOrderRequest(
            BrokerOrderRequest request,
            String orderType) {

        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            return "Quantity must be greater than zero";
        }

        if (request.getInstrumentKey() == null
                || request.getInstrumentKey().isBlank()) {
            return "Instrument key must not be blank";
        }

        String transactionType =
                request.getTransactionType() != null
                        && !request.getTransactionType().isBlank()
                        ? request.getTransactionType().trim().toUpperCase()
                        : request.getSignal() != null
                          ? request.getSignal().trim().toUpperCase()
                          : null;

        if (!"BUY".equals(transactionType)
                && !"SELL".equals(transactionType)) {
            return "Transaction type must be BUY or SELL";
        }

        if (!"MARKET".equals(orderType)
                && !"LIMIT".equals(orderType)
                && !"SL".equals(orderType)
                && !"SL-M".equals(orderType)) {
            return "Unsupported order type: " + orderType;
        }

        if (("LIMIT".equals(orderType) || "SL".equals(orderType))
                && (request.getPrice() == null
                || request.getPrice().compareTo(BigDecimal.ZERO) <= 0)) {
            return "A positive price is required for " + orderType + " orders";
        }

        if (("SL".equals(orderType) || "SL-M".equals(orderType))
                && (request.getTriggerPrice() == null
                || request.getTriggerPrice().compareTo(BigDecimal.ZERO) <= 0)) {
            return "A positive trigger price is required for " + orderType + " orders";
        }

        if (request.getTag() == null || request.getTag().isBlank()) {
            return "Order tag is required for safe order reconciliation";
        }

        return null;
    }

    private Mono<BrokerOrderResponse> reconcileAfterPlacementError(
            BrokerOrderRequest request,
            String tag,
            Throwable placementError) {

        String errorMessage = placementError != null
                ? placementError.getMessage()
                : "Unknown placement error";

        log.warn(
                "Reconciling uncertain broker placement | Symbol={} | Tag={} | Error={}",
                request.getSymbol(),
                tag,
                errorMessage);

        if (tag == null || tag.isBlank()) {
            return Mono.just(unknownPlacementOutcome(
                    "Order placement outcome is uncertain and no tag is available. "
                            + "Do not retry automatically."));
        }

        // Keep the remainder of your existing reconciliation logic here.

        return findOrderByTag(tag)
                .flatMap(order -> {
                    if (order.getOrderId() == null
                            || order.getOrderId().isBlank()) {
                        return Mono.just(unknownPlacementOutcome(
                                "Matching order history found, but order ID is missing. "
                                        + "Manual reconciliation is required."));
                    }

                    String brokerStatus = order.getStatus() == null
                            ? "UNKNOWN"
                            : order.getStatus().trim();

                    log.warn(
                            "Order reconciled after placement error | Symbol={} "
                                    + "| OrderId={} | Tag={} | BrokerStatus={}",
                            request.getSymbol(),
                            order.getOrderId(),
                            tag,
                            brokerStatus);

                    if ("rejected".equalsIgnoreCase(brokerStatus)) {
                        return Mono.just(BrokerOrderResponse.builder()
                                .success(false)
                                .orderId(order.getOrderId())
                                .exchangeOrderId(order.getExchangeOrderId())
                                .status("REJECTED")
                                .message(order.getStatusMessage() != null
                                        ? order.getStatusMessage()
                                        : "Order rejected by Upstox")
                                .build());
                    }

                    if ("cancelled".equalsIgnoreCase(brokerStatus)
                            || "canceled".equalsIgnoreCase(brokerStatus)) {
                        return Mono.just(BrokerOrderResponse.builder()
                                .success(false)
                                .orderId(order.getOrderId())
                                .exchangeOrderId(order.getExchangeOrderId())
                                .status("CANCELLED")
                                .message("Order was cancelled at the broker")
                                .build());
                    }

                    return Mono.just(BrokerOrderResponse.builder()
                            .success(true)
                            .orderId(order.getOrderId())
                            .exchangeOrderId(order.getExchangeOrderId())
                            .executedPrice(order.getAveragePrice())
                            .executedQuantity(order.getFilledQuantity())
                            .pendingQuantity(order.getPendingQuantity())
                            .status("ORDER_PLACED")
                            .message("Order found in broker history. "
                                    + "Current broker status: " + brokerStatus
                                    + ". Confirm final state using order details.")
                            .build());
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    // A 4xx response means Upstox refused the request before creating an
                    // order; combined with a confirmed empty tag lookup it is a definitive
                    // rejection. Timeouts and 5xx responses remain ambiguous.
                    if (isDefinitiveClientRejection(placementError)) {
                        String body = ((WebClientResponseException) placementError)
                                .getResponseBodyAsString();
                        log.warn("Upstox rejected order request | Symbol={} | Tag={} | HttpStatus={} | Body={}",
                                request.getSymbol(), tag,
                                ((WebClientResponseException) placementError).getStatusCode().value(), body);
                        return BrokerOrderResponse.builder()
                                .success(false)
                                .status("REJECTED")
                                .message("Upstox rejected the order request: " + body)
                                .build();
                    }
                    return unknownPlacementOutcome(
                            "No matching order found during reconciliation. "
                                    + "The placement outcome remains uncertain; "
                                    + "do not retry automatically.");
                }))
                .onErrorResume(reconciliationError -> {
                    log.error(
                            "Order reconciliation failed | Symbol={} | Tag={}",
                            request.getSymbol(), tag, reconciliationError);

                    return Mono.just(unknownPlacementOutcome(
                            "Unable to confirm broker order status. "
                                    + "Manual reconciliation is required; "
                                    + "do not retry automatically."));
                });
    }

    private BrokerOrderResponse unknownPlacementOutcome(String message) {
        return BrokerOrderResponse.builder()
                .success(false)
                .status("UNKNOWN")
                .message(message)
                .build();
    }

    private static boolean isDefinitiveClientRejection(Throwable error) {
        // 408 is a request timeout: the broker may still have processed the order.
        return error instanceof WebClientResponseException response
                && response.getStatusCode().is4xxClientError()
                && response.getStatusCode().value() != 408;
    }

    /** Marks failures that happened before any order request reached the broker. */
    private static final class AccessTokenUnavailableException extends RuntimeException {
        private AccessTokenUnavailableException(Throwable cause) {
            super(cause.getMessage() != null ? cause.getMessage() : "Access token unavailable", cause);
        }
    }
}
