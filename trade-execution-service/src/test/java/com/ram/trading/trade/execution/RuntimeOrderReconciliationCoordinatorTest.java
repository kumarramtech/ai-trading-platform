package com.ram.trading.trade.execution;

import com.ram.trading.trade.execution.readiness.ExecutionReadiness;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import com.ram.trading.trade.execution.service.RuntimeOrderReconciliationCoordinator;
import com.ram.trading.trade.execution.service.StartupRecoveryCoordinator;
import com.ram.trading.trade.execution.service.TradeExecutionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RuntimeOrderReconciliationCoordinatorTest {

    private TradeExecutionServiceImpl tradeExecutionService;
    private StartupRecoveryCoordinator startupRecoveryCoordinator;
    private ExecutionReadiness readiness;
    private ExecutedTradeRepository executedTradeRepository;
    private RuntimeOrderReconciliationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        tradeExecutionService = mock(TradeExecutionServiceImpl.class);
        startupRecoveryCoordinator = mock(StartupRecoveryCoordinator.class);
        readiness = new ExecutionReadiness();
        executedTradeRepository = mock(ExecutedTradeRepository.class);

        coordinator = new RuntimeOrderReconciliationCoordinator(
                tradeExecutionService,
                startupRecoveryCoordinator,
                readiness,
                executedTradeRepository);
    }

    @Test
    void skipsRuntimeCycleWhileStartupRecoveryIsRunning() {
        readiness.beginRecovery();

        coordinator.reconcileUnresolvedOrders();

        verifyNoInteractions(tradeExecutionService, startupRecoveryCoordinator,
                executedTradeRepository);
    }

    @Test
    void revalidatesReadinessOnlyAfterAllPendingTradesAreCleared() throws Exception {
        readiness.markFailed("Previous startup recovery failed");
        when(tradeExecutionService.reconcilePendingOrdersAtRuntime()).thenReturn(Mono.empty());
        when(executedTradeRepository.findExitPendingTrades()).thenReturn(List.of());
        when(executedTradeRepository.findEntryPendingTrades()).thenReturn(List.of());

        CountDownLatch recoveryTriggered = new CountDownLatch(1);
        doAnswer(invocation -> {
            recoveryTriggered.countDown();
            return null;
        }).when(startupRecoveryCoordinator).recoverBeforeAcceptingExecution();

        coordinator.reconcileUnresolvedOrders();

        assertTrue(recoveryTriggered.await(3, TimeUnit.SECONDS),
                "Startup recovery should be triggered after pending rows are cleared");

        verify(startupRecoveryCoordinator, times(1)).recoverBeforeAcceptingExecution();
    }

    @Test
    void doesNotRevalidateReadinessWhilePendingTradesRemain() throws Exception {
        readiness.markFailed("Previous startup recovery failed");
        when(tradeExecutionService.reconcilePendingOrdersAtRuntime()).thenReturn(Mono.empty());
        CountDownLatch pendingCheckCompleted = new CountDownLatch(1);
        when(executedTradeRepository.findExitPendingTrades()).thenAnswer(invocation -> {
            pendingCheckCompleted.countDown();
            return List.of(mock(ExecutedTradeRecord.class));
        });

        coordinator.reconcileUnresolvedOrders();

        assertTrue(pendingCheckCompleted.await(3, TimeUnit.SECONDS),
                "The pending-order check should complete");
        verify(startupRecoveryCoordinator, never()).recoverBeforeAcceptingExecution();
    }
}
