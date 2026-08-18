package com.ram.trading.trade.execution.service;

import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionResponse;
import reactor.core.publisher.Mono;

public interface TradeExecutionService {

    Mono<TradeExecutionResponse> executeTrade(
            TradeExecutionRequest request);
}