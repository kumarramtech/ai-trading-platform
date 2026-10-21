package com.ram.trading.trade.execution.service;

import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import com.ram.trading.trade.execution.readiness.ExecutionReadiness;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRecord;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRepository;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs startup reconciliation sequentially and opens the execution gate only
 * after all persisted pending trades have been reconciled and no orphaned
 * unresolved idempotency claim remains.
 *
 * Concurrent recovery runs are rejected so the application-ready event and a
 * later runtime revalidation cannot reconcile the same persisted orders at once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRecoveryCoordinator {

    private final TradeExecutionServiceImpl tradeExecutionService;
    private final ExecutionReadiness readiness;
    private final ExecutionIdempotencyRepository idempotencyRepository;
    private final ExecutedTradeRepository executedTradeRepository;

    private final AtomicBoolean recoveryRunning = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void recoverBeforeAcceptingExecution() {
        if (!recoveryRunning.compareAndSet(false, true)) {
            log.warn("STARTUP RECOVERY SKIPPED | Another recovery run is already in progress");
            return;
        }

        readiness.beginRecovery();
        log.warn("EXECUTION READINESS CLOSED | Startup recovery in progress");

        tradeExecutionService.recoverPendingExitsAfterStartup()
                .then(tradeExecutionService.recoverPendingEntriesAfterStartup())
                .then(validateNoOrphanedClaims())
                .then(recoverFilledClaimsWithoutPersistedTrade())
                // Recovery methods can intentionally return a PENDING response
                // when the broker outcome remains inconclusive. Such a response
                // is not a recovery success: independently verify persisted pending
                // rows before opening the execution gate.
                .then(validateNoPendingTradesRemain())
                .doOnSuccess(ignored -> {
                    readiness.markReady();
                    log.info("EXECUTION READINESS OPEN | Startup recovery completed");
                })
                .doOnError(error -> {
                    readiness.markFailed(safeMessage(error));
                    log.error(
                            "EXECUTION READINESS CLOSED | Startup recovery failed. "
                                    + "New execution requests remain blocked.",
                            error);
                })
                .doFinally(signal -> recoveryRunning.set(false))
                .subscribe(ignored -> { }, error -> { });
    }

    /**
     * Reconcile orphaned unresolved claims by their existing broker order ID or
     * deterministic tag. The service never resubmits orders from this path.
     */
    private Mono<Void> validateNoOrphanedClaims() {
        return idempotencyRepository.findUnresolved()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::recoverOrValidateClaim)
                .then();
    }

    /**
     * A FILLED or PARTIAL_FILLED idempotency claim without an executed_trades row
     * represents possible real broker exposure that is not tracked locally. Recover
     * it before readiness can open; never treat the claim alone as proof that
     * position persistence succeeded.
     */
    private Mono<Void> recoverFilledClaimsWithoutPersistedTrade() {
        return idempotencyRepository.findFilledClaimsWithoutTrade()
                .flatMapMany(Flux::fromIterable)
                .concatMap(claim -> {
                    log.error(
                            "FILLED CLAIM WITHOUT TRADE ROW | Startup recovery required | SignalId={} | OrderId={}",
                            claim.getSignalId(), claim.getOrderId());

                    return tradeExecutionService.recoverUnresolvedClaim(claim)
                            .then(Mono.fromCallable(() -> {
                                ExecutedTradeRecord trade = executedTradeRepository
                                        .findBySignalId(claim.getSignalId())
                                        .orElseThrow(() -> new IllegalStateException(
                                                "Broker claim is FILLED but no executed trade was persisted after recovery. "
                                                        + "signalId=" + claim.getSignalId()));

                                String status = trade.getStatus() == null
                                        ? "" : trade.getStatus().trim().toUpperCase();
                                if (trade.getFilledQuantity() == null
                                        || trade.getFilledQuantity() <= 0
                                        || !("OPEN".equals(status) || "EXIT_PENDING".equals(status)
                                        || "CLOSED".equals(status))) {
                                    throw new IllegalStateException(
                                            "Recovered filled claim has invalid persisted exposure. signalId="
                                                    + claim.getSignalId() + ", status=" + status);
                                }
                                return true;
                            }).subscribeOn(Schedulers.boundedElastic()).then());
                })
                .then();
    }

    /**
     * A reconciliation method may return a PENDING response instead of raising
     * an error. Readiness must therefore be gated by persisted trade state too,
     * not only by whether the reactive recovery chain completed without error.
     */
    private Mono<Void> validateNoPendingTradesRemain() {
        return Mono.fromCallable(() -> {
                    var pendingExits = executedTradeRepository.findExitPendingTrades();
                    var pendingEntries = executedTradeRepository.findEntryPendingTrades();

                    if (!pendingExits.isEmpty() || !pendingEntries.isEmpty()) {
                        StringBuilder reason = new StringBuilder(
                                "Startup recovery left unresolved execution trades.");
                        if (!pendingExits.isEmpty()) {
                            reason.append(" EXIT_PENDING signalIds=")
                                    .append(pendingExits.stream()
                                            .map(ExecutedTradeRecord::getSignalId)
                                            .toList());
                        }
                        if (!pendingEntries.isEmpty()) {
                            reason.append(" ENTRY_PENDING signalIds=")
                                    .append(pendingEntries.stream()
                                            .map(ExecutedTradeRecord::getSignalId)
                                            .toList());
                        }
                        throw new IllegalStateException(reason.toString());
                    }
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Mono<Void> recoverOrValidateClaim(ExecutionIdempotencyRecord claim) {
        return Mono.fromCallable(() -> executedTradeRepository.findBySignalId(claim.getSignalId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optional -> {
                    if (optional.isPresent()) {
                        ExecutedTradeRecord trade = optional.get();
                        String status = trade.getStatus() == null ? "UNKNOWN" : trade.getStatus();
                        if ("ENTRY_PENDING".equalsIgnoreCase(status)
                                || "EXIT_PENDING".equalsIgnoreCase(status)) {
                            return Mono.error(new IllegalStateException(
                                    "Pending trade remains unresolved after startup recovery. signalId="
                                            + claim.getSignalId() + ", status=" + status));
                        }

                        // A crash can occur after the confirmed entry fill is persisted
                        // but before its idempotency claim is made terminal. Repair only
                        // when the persisted trade and claim agree on the entry identity.
                        if ("OPEN".equalsIgnoreCase(status) || "EXIT_PENDING".equalsIgnoreCase(status)
                                || "CLOSED".equalsIgnoreCase(status)) {
                            return repairTerminalEntryClaim(claim, trade);
                        }
                        return Mono.empty();
                    }
                    log.warn(
                            "ORPHANED IDEMPOTENCY CLAIM | Attempting safe broker reconciliation only | SignalId={} | OrderId={} | Tag={}",
                            claim.getSignalId(), claim.getOrderId(), claim.getTag());
                    return tradeExecutionService.recoverUnresolvedClaim(claim).then();
                });
    }

    private Mono<Void> repairTerminalEntryClaim(
            ExecutionIdempotencyRecord claim,
            ExecutedTradeRecord trade) {
        String entryOrderId = trade.getEntryOrderId();
        Integer requested = trade.getRequestedQuantity();
        Integer filled = trade.getFilledQuantity();

        if (claim.getSignalId() == null
                || !claim.getSignalId().equals(trade.getSignalId())
                || entryOrderId == null || entryOrderId.isBlank()
                || claim.getOrderId() == null || !claim.getOrderId().equals(entryOrderId)
                || requested == null || requested <= 0
                || claim.getQuantity() == null || !claim.getQuantity().equals(requested)
                || filled == null || filled <= 0 || filled > requested
                || claim.getSymbol() == null || trade.getSymbol() == null
                || !claim.getSymbol().equalsIgnoreCase(trade.getSymbol())
                || claim.getSignal() == null || trade.getSide() == null
                || !claim.getSignal().equalsIgnoreCase(trade.getSide())
                || claim.getInstrumentKey() == null || trade.getInstrumentKey() == null
                || !claim.getInstrumentKey().equalsIgnoreCase(trade.getInstrumentKey())
                || trade.getAverageEntryPrice() == null
                || trade.getAverageEntryPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return Mono.error(new IllegalStateException(
                    "Persisted trade conflicts with unresolved idempotency claim; manual reconciliation required. signalId="
                            + claim.getSignalId()));
        }

        if (filled.equals(requested)) {
            log.warn("REPAIRING ENTRY CLAIM AFTER PERSISTED FULL FILL | SignalId={} | OrderId={}",
                    claim.getSignalId(), entryOrderId);
            return idempotencyRepository.updateFilled(
                    claim.getSignalId(), entryOrderId, filled, trade.getAverageEntryPrice());
        }

        log.warn("REPAIRING ENTRY CLAIM AFTER PERSISTED PARTIAL FILL | SignalId={} | OrderId={} | FilledQty={}",
                claim.getSignalId(), entryOrderId, filled);
        return idempotencyRepository.updatePartialFilled(
                claim.getSignalId(), entryOrderId, filled, trade.getAverageEntryPrice(),
                "Recovered terminal partial entry fill from persisted trade after interrupted claim update");
    }

    private String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null
                ? "Unknown startup recovery error" : error.getMessage();
    }
}
