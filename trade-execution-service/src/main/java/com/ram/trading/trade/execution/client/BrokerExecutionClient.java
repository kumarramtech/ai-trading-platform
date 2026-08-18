package com.ram.trading.trade.execution.client;


import com.ram.trading.trade.execution.dto.BrokerOrderRequest;
import com.ram.trading.trade.execution.dto.BrokerOrderResponse;
import reactor.core.publisher.Mono;

public interface BrokerExecutionClient {

    Mono<BrokerOrderResponse> executeOrder(
            BrokerOrderRequest request);
}