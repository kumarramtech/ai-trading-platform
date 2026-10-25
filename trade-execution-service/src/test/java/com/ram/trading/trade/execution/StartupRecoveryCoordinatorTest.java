package com.ram.trading.trade.execution;

import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import com.ram.trading.trade.execution.readiness.ExecutionReadiness;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRecord;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRepository;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import com.ram.trading.trade.execution.service.StartupRecoveryCoordinator;
import com.ram.trading.trade.execution.service.TradeExecutionServiceImpl;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StartupRecoveryCoordinatorTest {

    @Test
    void repairsPendingClaimWhenMatchingPartialTradeWasAlreadyPersisted() throws Exception {
        TradeExecutionServiceImpl service = mock(TradeExecutionServiceImpl.class);
        ExecutionReadiness readiness = new ExecutionReadiness();
        ExecutionIdempotencyRepository idempotency = mock(ExecutionIdempotencyRepository.class);
        ExecutedTradeRepository trades = mock(ExecutedTradeRepository.class);

        ExecutionIdempotencyRecord claim = new ExecutionIdempotencyRecord(
                701L, "AI-701", "TCS", "BUY", 2, "PENDING", "entry-701",
                1, new BigDecimal("100.00"), "partial fill", "NSE_EQ|TEST",
                new BigDecimal("100.00"), null, new BigDecimal("95.00"),
                new BigDecimal("110.00"), null, null);
        ExecutedTradeRecord trade = ExecutedTradeRecord.builder()
                .signalId(701L).symbol("TCS").instrumentKey("NSE_EQ|TEST")
                .side("BUY").requestedQuantity(2).filledQuantity(1)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("OPEN").entryOrderId("entry-701")
                .exitFilledQuantity(0).remainingQuantity(1).build();

        CountDownLatch claimRepaired = new CountDownLatch(1);
        when(service.expirePriorSessionState()).thenReturn(Mono.empty());
        when(service.recoverPendingExitsAfterStartup()).thenReturn(Mono.empty());
        when(service.recoverPendingEntriesAfterStartup()).thenReturn(Mono.empty());
        when(idempotency.findUnresolved()).thenReturn(Mono.just(List.of(claim)));
        when(trades.findBySignalId(701L)).thenReturn(Optional.of(trade));
        when(idempotency.updatePartialFilled(eq(701L), eq("entry-701"), eq(1),
                eq(new BigDecimal("100.00")), any())).thenAnswer(invocation -> {
            claimRepaired.countDown();
            return Mono.empty();
        });
        when(idempotency.findFilledClaimsWithoutTrade()).thenReturn(Mono.just(List.of()));
        when(trades.findExitPendingTrades()).thenReturn(List.of());
        when(trades.findEntryPendingTrades()).thenReturn(List.of());

        StartupRecoveryCoordinator coordinator = new StartupRecoveryCoordinator(
                service, readiness, idempotency, trades);
        coordinator.recoverBeforeAcceptingExecution();

        assertTrue(claimRepaired.await(3, TimeUnit.SECONDS),
                "The persisted partial fill should repair its unresolved idempotency claim");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!readiness.isReady() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(readiness.isReady(), "Readiness should open after the matching claim is repaired");
        verify(idempotency).updatePartialFilled(eq(701L), eq("entry-701"), eq(1),
                eq(new BigDecimal("100.00")), any());
    }
}
