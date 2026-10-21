package com.ram.trading.trade.execution.client;

import com.ram.trading.trade.execution.dto.BrokerOrderRequest;
import com.ram.trading.trade.execution.dto.BrokerOrderResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderDetailsResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderHistoryResponse;
import reactor.core.publisher.Mono;

public interface BrokerExecutionClient {

    Mono<BrokerOrderResponse> executeOrder(BrokerOrderRequest request);

    default Mono<BrokerOrderResponse> executeExitOrder(
            BrokerOrderRequest request) {
        return executeOrder(request);
    }

    Mono<UpstoxOrderDetailsResponse.DataResponse> getOrderDetails(
            String orderId);

    Mono<UpstoxOrderHistoryResponse.OrderData> findOrderByTag(
            String tag);

    /**
     * Requests cancellation of an open/pending broker order.
     * A true result means the broker acknowledged the request only; callers must
     * still fetch order details and verify a terminal status.
     */
    Mono<Boolean> cancelOrder(String orderId);
}