package com.ram.trading.trade.execution;

import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRepository;
import com.ram.trading.trade.execution.risk.ExecutionRiskGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionRiskGuardTest {

    private ExecutedTradeRepository trades;
    private ExecutionIdempotencyRepository claims;
    private ExecutionRiskGuard guard;

    @BeforeEach
    void setUp() {
        trades = mock(ExecutedTradeRepository.class);
        claims = mock(ExecutionIdempotencyRepository.class);
        guard = new ExecutionRiskGuard(trades, claims, "Asia/Kolkata",
                new BigDecimal("10000"), 2, 6, Clock.systemDefaultZone());
    }

    @Test
    void secondPositionInTheSameSymbolIsRejected() {
        when(trades.countActivePositionsForSymbol("TCS")).thenReturn(1);

        String rejection = guard.evaluateNewEntry(request("TCS"));

        assertNotNull(rejection);
        assertTrue(rejection.contains("TCS"), rejection);
    }

    @Test
    void unresolvedClaimInTheSameSymbolIsRejected() {
        when(claims.countUnresolvedClaimsWithoutTradeForSymbol("TCS")).thenReturn(1);

        assertNotNull(guard.evaluateNewEntry(request("TCS")));
    }

    @Test
    void entryInAnotherSymbolWithinLimitsIsAllowed() {
        when(trades.countActivePositionsForSymbol("TCS")).thenReturn(1);
        when(trades.countActivePositions()).thenReturn(1);

        assertNull(guard.evaluateNewEntry(request("INFY")));
    }

    private static TradeExecutionRequest request(String symbol) {
        return TradeExecutionRequest.builder()
                .signalId(1L)
                .symbol(symbol)
                .signal("BUY")
                .quantity(10)
                .entryPrice(new BigDecimal("100"))
                .requiredMargin(new BigDecimal("5000"))
                .stopLoss(new BigDecimal("99"))
                .build();
    }
}
