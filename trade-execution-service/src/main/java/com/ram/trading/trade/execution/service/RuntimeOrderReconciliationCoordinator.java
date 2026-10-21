package com.ram.trading.trade.execution.service;

import com.ram.trading.trade.execution.readiness.ExecutionReadiness;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically revisits unresolved broker orders while the process is running.
 * Reconciliation is safe to run while execution readiness is FAILED because it
 * only checks existing broker orders; it never opens new orders itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuntimeOrderReconciliationCoordinator {

    private final TradeExecutionServiceImpl tradeExecutionService;
    private final StartupRecoveryCoordinator startupRecoveryCoordinator;
    private final ExecutionReadiness readiness;
    private final ExecutedTradeRepository executedTradeRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            fixedDelayString = "${execution.runtime-reconciliation.interval-ms:30000}",
            initialDelayString = "${execution.runtime-reconciliation.initial-delay-ms:30000}")
    public void reconcileUnresolvedOrders() {
        if (readiness.snapshot().state() == ExecutionReadiness.State.RECOVERING) {
            log.debug("RUNTIME RECONCILIATION SKIPPED | Startup recovery is still running");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("RUNTIME RECONCILIATION SKIPPED | Previous reconciliation cycle is still running");
            return;
        }

        log.debug("RUNTIME ORDER RECONCILIATION STARTED");
        tradeExecutionService.reconcilePendingOrdersAtRuntime()
                .then(Mono.fromCallable(() ->
                        executedTradeRepository.findExitPendingTrades().isEmpty()
                                && executedTradeRepository.findEntryPendingTrades().isEmpty())
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnNext(noPendingOrders -> {
                    if (noPendingOrders && !readiness.isReady()) {
                        // Re-run the complete startup validation (including idempotency
                        // claims) before reopening execution after a failed startup gate.
                        log.info("RUNTIME RECONCILIATION CLEARED PENDING ORDERS | Revalidating execution readiness");
                        startupRecoveryCoordinator.recoverBeforeAcceptingExecution();
                    }
                })
                .doOnSuccess(noPendingOrders -> log.debug(
                        "RUNTIME ORDER RECONCILIATION FINISHED | NoPendingOrders={}", noPendingOrders))
                .doOnError(error -> log.error(
                        "RUNTIME ORDER RECONCILIATION CYCLE FAILED | Execution readiness is unchanged", error))
                .doFinally(signal -> running.set(false))
                .subscribe(ignored -> { }, error -> { });
    }
}
