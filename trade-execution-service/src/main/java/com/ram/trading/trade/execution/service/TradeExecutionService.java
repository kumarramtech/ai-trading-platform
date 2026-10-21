package com.ram.trading.trade.execution.service;

import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionResponse;
import com.ram.trading.trade.execution.dto.TradeExitRequest;
import com.ram.trading.trade.execution.dto.TradeProtectionUpdateRequest;
import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import java.util.List;
import reactor.core.publisher.Mono;

public interface TradeExecutionService {

    Mono<TradeExecutionResponse> executeTrade(
            TradeExecutionRequest request);

    /** Read-only view of persisted OPEN execution positions. */
    Mono<List<ExecutedTradeRecord>> getOpenTrades();

    Mono<TradeExecutionResponse> exitTrade(
            TradeExitRequest request);

    /** Persist safer stop-loss and/or target levels for an OPEN position. */
    Mono<ExecutedTradeRecord> updateProtection(TradeProtectionUpdateRequest request);
}
