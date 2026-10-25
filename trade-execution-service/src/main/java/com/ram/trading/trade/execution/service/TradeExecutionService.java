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

    /**
     * Read-only view of every position opened in the current trading session, any status.
     * Signal Engine uses it to enforce its own trading-risk limits in live mode.
     */
    Mono<List<ExecutedTradeRecord>> getSessionTrades();

    Mono<TradeExecutionResponse> exitTrade(
            TradeExitRequest request);

    /** Persist safer stop-loss and/or target levels for an OPEN position. */
    Mono<ExecutedTradeRecord> updateProtection(TradeProtectionUpdateRequest request);
}
