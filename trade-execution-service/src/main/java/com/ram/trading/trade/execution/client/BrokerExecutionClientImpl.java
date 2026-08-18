package com.ram.trading.trade.execution.client;

import com.ram.trading.trade.execution.client.BrokerExecutionClient;
import com.ram.trading.trade.execution.dto.BrokerOrderRequest;
import com.ram.trading.trade.execution.dto.BrokerOrderResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderRequest;
import com.ram.trading.trade.execution.dto.UpstoxOrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class BrokerExecutionClientImpl implements BrokerExecutionClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${upstox.order-url}")
    private String upstoxOrderUrl;

    private final BrokerClient brokerClient;

    @Override
    public Mono<BrokerOrderResponse> executeOrder(
            BrokerOrderRequest request) {

        log.info("========================================");
        log.info("UPSTOX ORDER EXECUTION STARTED");
        log.info(
                "Symbol={} | InstrumentKey={} | Signal={} | Quantity={} | Price={} | OrderType={}",
                request.getSymbol(),
                request.getInstrumentKey(),
                request.getSignal(),
                request.getQuantity(),
                request.getPrice(),
                request.getOrderType()
        );

        UpstoxOrderRequest upstoxOrderRequest =
                buildUpstoxOrderRequest(request);

        log.info(
                "Sending order to Upstox | Instrument={} | Transaction={} | Product=I",
                request.getInstrumentKey(),
                request.getSignal()
        );

        return brokerClient.getAccessToken()
                .flatMap(accessToken ->

                        webClientBuilder
                                .build()
                                .post()
                                .uri(upstoxOrderUrl)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + accessToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(upstoxOrderRequest)
                                .retrieve()
                                .bodyToMono(UpstoxOrderResponse.class)
                )
                .map(response ->
                        buildBrokerOrderResponse(request, response)
                )
                .onErrorResume(error -> {
                    log.error(
                            "Upstox order execution failed | Symbol={}",
                            request.getSymbol(),
                            error
                    );

                    return Mono.just(
                            BrokerOrderResponse.builder()
                                    .success(false)
                                    .status("FAILED")
                                    .message(
                                            error.getMessage() != null
                                                    ? error.getMessage()
                                                    : "Unknown error while placing Upstox order"
                                    )
                                    .build()
                    );
                });
    }

    private UpstoxOrderRequest buildUpstoxOrderRequest(
            BrokerOrderRequest request) {

        return UpstoxOrderRequest.builder()
                .quantity(request.getQuantity())
                .product("I")
                .validity("DAY")
                .price(request.getPrice())
                .instrumentToken(request.getInstrumentKey())
                .orderType(request.getOrderType())
                .transactionType(request.getSignal())
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

        /*
         * Expected Upstox success response:
         *
         * {
         *   "status": "success",
         *   "data": {
         *      "order_ids": [
         *          "123456789"
         *      ]
         *   }
         * }
         */

        if (!"success".equalsIgnoreCase(
                response.getStatus())) {

            return BrokerOrderResponse.builder()
                    .success(false)
                    .status("REJECTED")
                    .message("Upstox rejected the order")
                    .build();
        }

        String orderId = null;

        if (response.getData() != null
                && response.getData().getOrderIds() != null
                && !response.getData()
                .getOrderIds()
                .isEmpty()) {

            orderId = response.getData()
                    .getOrderIds()
                    .get(0);
        }

        log.info(
                "UPSTOX ORDER ACCEPTED | Symbol={} | OrderId={}",
                request.getSymbol(),
                orderId
        );

        return BrokerOrderResponse.builder()
                .success(true)
                .orderId(orderId)

                // Order accepted, not necessarily filled yet
                .status("ORDER_PLACED")

                .executedPrice(null)

                .message(
                        "Order successfully placed with Upstox"
                )
                .build();
    }
}