package com.ram.trading.trade.execution.service;

import com.ram.trading.trade.execution.client.BrokerExecutionClient;
import com.ram.trading.trade.execution.dto.*;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRecord;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRepository;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import com.ram.trading.trade.execution.readiness.ExecutionReadiness;
import com.ram.trading.trade.execution.risk.ExecutionRiskGuard;


import org.springframework.dao.DuplicateKeyException;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExecutionServiceImpl implements TradeExecutionService {

    private final BrokerExecutionClient brokerExecutionClient;
    private final ExecutionIdempotencyRepository idempotencyRepository;
    private final ExecutedTradeRepository executedTradeRepository;
    private final ExecutionReadiness executionReadiness;
    private final ExecutionRiskGuard riskGuard;

    /** Serializes limit evaluation with claim insertion so concurrent entries cannot both pass a limit. */
    private final ReentrantLock entryAdmissionLock = new ReentrantLock();

    /**
     * Signal IDs with a protective-stop or exit operation in flight. A market exit
     * must never race a stop placement/modification for the same position, or both
     * could fill and reverse it. In-process only: run a single service instance.
     */
    private final Set<Long> positionsInFlight = ConcurrentHashMap.newKeySet();

    @Value("${execution.live-enabled:false}")
    private boolean liveEnabled;

    @Value("${execution.default-order-type:MARKET}")
    private String defaultOrderType;

    @Value("${execution.reconciliation.max-attempts:10}")
    private long reconciliationMaxAttempts;

    @Value("${execution.reconciliation.interval-ms:500}")
    private long reconciliationIntervalMs;

    /** Upstox/product-specific support must be confirmed before enabling live orders. */
    @Value("${execution.protection.order-type:SL-M}")
    private String protectiveStopOrderType;

    /** Exchange tick that every stop/target sent to the broker is aligned to. */
    @Value("${execution.price.tick-size:0.05}")
    private BigDecimal priceTickSize = new BigDecimal("0.05");

    /**
     * An exit claim whose order never reached the broker (no order ID, and broker
     * history confirms no order under its tag) is released back to OPEN after this
     * grace period, so the position can be exited again instead of staying stuck.
     */
    @Value("${execution.exit.unsubmitted-claim-release-seconds:60}")
    private long unsubmittedExitClaimReleaseSeconds = 60L;

    /**
     * Recover persisted exit orders after application startup.
     *
     * Existing order IDs are reconciled directly. If the order ID is missing,
     * search broker history using the deterministic original exit tag.
     * Never submit a replacement order during startup recovery.
     */
    public Mono<Void> recoverPendingExitsAfterStartup() {
        return Mono.fromCallable(() -> executedTradeRepository.findExitPendingTrades())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .concatMap(trade -> {
                    if (trade == null || trade.getSignalId() == null) {
                        log.error("Invalid EXIT_PENDING record during startup recovery");
                        return Mono.error(new IllegalStateException(
                                "Invalid EXIT_PENDING record; startup recovery cannot be certified"));
                    }

                    Long signalId = trade.getSignalId();
                    String orderId = trade.getExitOrderId();

                    Integer persistedQuantity = trade.getExitOrderQuantity();
                    int priorExitFilled =
                            valueOrZero(trade.getExitFilledQuantity());

                    int remaining = trade.getRemainingQuantity() == null
                            ? Math.max(
                            0,
                            valueOrZero(trade.getFilledQuantity())
                            - priorExitFilled)
                            : trade.getRemainingQuantity();

                    if (remaining <= 0) {
                        log.error(
                                "EXIT_PENDING trade has invalid remaining quantity. "
                                        + "SignalId={}, Remaining={}",
                                signalId,
                                remaining);
                        return Mono.error(new IllegalStateException(
                                "Invalid EXIT_PENDING remaining quantity for signalId=" + signalId));
                    }

                    if (persistedQuantity == null || persistedQuantity <= 0) {
                        log.error(
                                "Cannot recover EXIT_PENDING trade because the "
                                        + "original exit quantity is missing. "
                                        + "SignalId={}, OrderId={}. "
                                        + "Manual broker reconciliation required.",
                                signalId,
                                orderId);
                        return Mono.error(new IllegalStateException(
                                "Missing original exit quantity for signalId=" + signalId));
                    }

                    TradeExitRequest recoveryRequest =
                            TradeExitRequest.builder()
                                    .signalId(signalId)
                                    .quantity(persistedQuantity)
                                    .orderType("MARKET")
                                    .build();

                    // Case 1: The broker order ID is already persisted.
                    if (!isBlank(orderId)) {
                        log.info(
                                "Recovering persisted exit order. "
                                        + "SignalId={}, OrderId={}, Quantity={}",
                                signalId,
                                orderId,
                                persistedQuantity);

                        return reconcileExitOrder(
                                recoveryRequest,
                                trade,
                                orderId,
                                0)
                                .doOnNext(response -> log.info(
                                        "Startup exit reconciliation finished. "
                                                + "SignalId={}, OrderId={}, Status={}",
                                        signalId,
                                        orderId,
                                        response.getStatus()))
                                .onErrorResume(error -> {
                                    log.error(
                                            "Startup exit reconciliation failed. "
                                                    + "SignalId={}, OrderId={}. "
                                                    + "Trade remains subject to reconciliation.",
                                            signalId,
                                            orderId,
                                            error);
                                    return Mono.error(new IllegalStateException(
                                            "Exit startup reconciliation failed for signalId=" + signalId,
                                            error));
                                })
                                .then();
                    }

                    // Case 2: The order ID is missing. Search by the original tag.
                    String tag = buildExitTag(
                            signalId,
                            trade.getExitAttempt(),
                            priorExitFilled,
                            persistedQuantity);

                    log.info(
                            "Searching broker history for pending exit. "
                                    + "SignalId={}, Tag={}, Quantity={}",
                            signalId,
                            tag,
                            persistedQuantity);

                    return brokerExecutionClient.findOrderByTag(tag)
                            .flatMap(existing -> {
                                String recoveredOrderId =
                                        existing.getOrderId();

                                if (isBlank(recoveredOrderId)) {
                                    log.error(
                                            "Broker history returned a matching "
                                                    + "exit without an order ID. "
                                                    + "SignalId={}, Tag={}. "
                                                    + "No replacement order will be submitted.",
                                            signalId,
                                            tag);
                                    return Mono.just(false);
                                }

                                log.info(
                                        "Recovered exit order ID from broker history. "
                                                + "SignalId={}, OrderId={}, Tag={}",
                                        signalId,
                                        recoveredOrderId,
                                        tag);

                                return persistExitOrderId(
                                        trade,
                                        recoveredOrderId,
                                        persistedQuantity)
                                        .then(Mono.defer(() ->
                                                reconcileExitOrder(
                                                        recoveryRequest,
                                                        trade,
                                                        recoveredOrderId,
                                                        0)))
                                        .doOnNext(response -> log.info(
                                                "Recovered exit reconciliation finished. "
                                                        + "SignalId={}, OrderId={}, Status={}",
                                                signalId,
                                                recoveredOrderId,
                                                response.getStatus()))
                                        .thenReturn(true);
                            })
                            .defaultIfEmpty(false)
                            .onErrorResume(error -> {
                                log.error(
                                        "Broker lookup or exit recovery failed. "
                                                + "SignalId={}, Tag={}. "
                                                + "Trade remains pending; "
                                                + "no replacement order will be submitted.",
                                        signalId,
                                        tag,
                                        error);
                                return Mono.error(new IllegalStateException(
                                        "Exit order could not be recovered for signalId=" + signalId,
                                        error));
                            })
                            .flatMap(found -> {
                                if (!found) {
                                    return Mono.error(new IllegalStateException(
                                            "No confirmed exit order recovered for signalId=" + signalId));
                                }
                                return Mono.just(found);
                            })
                            .doOnNext(found -> {
                                if (!found) {
                                    log.warn(
                                            "No confirmed exit order recovered. "
                                                    + "SignalId={}, Tag={}. "
                                                    + "Manual broker reconciliation "
                                                    + "is required before any retry.",
                                            signalId,
                                            tag);
                                }
                            })
                            .then();
                })
                .then()
                .doOnError(error ->
                        log.error(
                                "Could not load pending exits during startup recovery",
                                error));
    }


    /**
     * Runtime reconciliation for persisted orders that remain unresolved after
     * the initial startup pass. This path never submits replacement orders and
     * does not cancel an entry order merely because it is still working.
     * Per-order broker failures are logged and isolated so one bad order does
     * not prevent other unresolved orders from being checked on the next cycle.
     */
    public Mono<Void> reconcilePendingOrdersAtRuntime() {
        return expirePriorSessionState()
                .onErrorResume(error -> {
                    log.error("PRIOR-SESSION EXPIRY FAILED | Continuing with order reconciliation", error);
                    return Mono.empty();
                })
                .then(reconcilePendingExitsAndEntriesAtRuntime())
                .then(reconcileProtectiveStopsAtRuntime());
    }

    private Mono<Void> reconcilePendingExitsAndEntriesAtRuntime() {
        return Mono.fromCallable(executedTradeRepository::findExitPendingTrades)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .concatMap(trade -> reconcilePendingExitAtRuntime(trade)
                        .onErrorResume(error -> {
                            log.error("RUNTIME EXIT RECONCILIATION FAILED | SignalId={} | OrderId={} | No replacement order submitted",
                                    trade == null ? null : trade.getSignalId(),
                                    trade == null ? null : trade.getExitOrderId(), error);
                            return Mono.empty();
                        }))
                .then(Mono.fromCallable(executedTradeRepository::findEntryPendingTrades)
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                        .concatMap(trade -> reconcilePendingEntryAtRuntime(trade)
                                .onErrorResume(error -> {
                                    log.error("RUNTIME ENTRY RECONCILIATION FAILED | SignalId={} | OrderId={} | Order left unresolved; no replacement submitted",
                                            trade == null ? null : trade.getSignalId(),
                                            trade == null ? null : trade.getEntryOrderId(), error);
                                    return Mono.empty();
                                }))
                        .then());
    }

    private Mono<Void> reconcilePendingExitAtRuntime(ExecutedTradeRecord trade) {
        if (trade == null || trade.getSignalId() == null) {
            log.error("RUNTIME EXIT RECONCILIATION SKIPPED | Invalid persisted EXIT_PENDING record");
            return Mono.empty();
        }

        int quantity = trade.getExitOrderQuantity() == null ? 0 : trade.getExitOrderQuantity();
        int remaining = trade.getRemainingQuantity() == null
                ? Math.max(0, valueOrZero(trade.getFilledQuantity())
                    - valueOrZero(trade.getExitFilledQuantity()))
                : trade.getRemainingQuantity();
        if (quantity <= 0 || remaining <= 0) {
            log.error("RUNTIME EXIT RECONCILIATION SKIPPED | Invalid persisted quantities | SignalId={} | ExitQuantity={} | Remaining={}",
                    trade.getSignalId(), quantity, remaining);
            return Mono.empty();
        }

        TradeExitRequest request = TradeExitRequest.builder()
                .signalId(trade.getSignalId())
                .quantity(quantity)
                .orderType("MARKET")
                .build();

        String orderId = trade.getExitOrderId();
        if (!isBlank(orderId)) {
            return reconcileExitOrder(request, trade, orderId, 0).then();
        }

        String tag = buildExitTag(
                trade.getSignalId(), trade.getExitAttempt(),
                valueOrZero(trade.getExitFilledQuantity()), quantity);
        return brokerExecutionClient.findOrderByTag(tag)
                .flatMap(history -> {
                    if (history == null || isBlank(history.getOrderId())) {
                        // A matching history record without an order ID is not
                        // equivalent to a successful lookup with no matching order.
                        // Keep the trade unresolved and surface this malformed result.
                        return Mono.error(new IllegalStateException(
                                "Broker history matched the exit tag but returned no usable order ID. "
                                        + "signalId=" + trade.getSignalId() + ", tag=" + tag));
                    }

                    String recoveredOrderId = history.getOrderId();
                    return persistExitOrderId(trade, recoveredOrderId, quantity)
                            .then(Mono.defer(() -> reconcileExitOrder(
                                    request, trade, recoveredOrderId, 0)))
                            .thenReturn(Boolean.TRUE);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // BrokerExecutionClientImpl emits empty only after a successful,
                    // valid history response with no matching tag. Transport, auth,
                    // malformed-response, and unsuccessful-response errors propagate
                    // to the caller's error handler; none of those authorize a retry.
                    // The claim never reached the broker (e.g. the request was interrupted
                    // between claim and submission). Leaving it EXIT_PENDING would block
                    // every later exit, including market-close square-off, while the
                    // broker stop may already be cancelled. Release it once it is old
                    // enough that a just-submitted order would be visible in history.
                    return Mono.fromCallable(() -> executedTradeRepository.releaseUnsubmittedExitClaim(
                                    trade.getSignalId(), valueOrZero(trade.getExitAttempt()),
                                    unsubmittedExitClaimReleaseSeconds))
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnNext(released -> {
                                if (released) {
                                    log.error("UNSUBMITTED EXIT CLAIM RELEASED | SignalId={} | Tag={} | No broker order exists for this attempt; "
                                                    + "position is OPEN again and will be exited by the next exit request",
                                            trade.getSignalId(), tag);
                                } else {
                                    log.warn("RUNTIME EXIT TAG LOOKUP CONFIRMED NO MATCH | SignalId={} | Tag={} | Claim is recent or changed; "
                                                    + "trade remains pending; no replacement order submitted",
                                            trade.getSignalId(), tag);
                                }
                            })
                            .then(Mono.<Boolean>empty());
                }))
                .then();
    }

    private Mono<Void> reconcilePendingEntryAtRuntime(ExecutedTradeRecord trade) {
        if (trade == null || trade.getSignalId() == null || isBlank(trade.getEntryOrderId())) {
            log.error("RUNTIME ENTRY RECONCILIATION SKIPPED | Invalid persisted ENTRY_PENDING record");
            return Mono.empty();
        }

        TradeExecutionRequest request = TradeExecutionRequest.builder()
                .signalId(trade.getSignalId())
                .symbol(trade.getSymbol())
                .instrumentKey(trade.getInstrumentKey())
                .signal(trade.getSide())
                .quantity(trade.getRequestedQuantity())
                .stopLoss(trade.getInitialStopLoss())
                .target(trade.getTargetPrice())
                .entryPrice(trade.getAverageEntryPrice())
                .orderType("MARKET")
                .build();

        String orderId = trade.getEntryOrderId();
        return brokerExecutionClient.getOrderDetails(orderId)
                .flatMap(details -> {
                    if (details == null) {
                        return Mono.just(false);
                    }
                    String status = normalizeStatus(details.getStatus());
                    if (isComplete(status) || isRejectedOrCancelled(status)) {
                        return reconcileDetails(request, orderId, details, 1).thenReturn(true);
                    }
                    // A working entry can represent live exposure. Do not cancel it
                    // from a periodic reconciliation pass; wait for a terminal broker
                    // state and check again on the next interval.
                    log.info("RUNTIME ENTRY ORDER STILL WORKING | SignalId={} | OrderId={} | Status={}; leaving it pending",
                            trade.getSignalId(), orderId, status);
                    return Mono.just(true);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("RUNTIME ENTRY ORDER DETAILS EMPTY | SignalId={} | OrderId={} | Keeping pending state",
                            trade.getSignalId(), orderId);
                    return Mono.just(false);
                }))
                .then();
    }

    /**
     * Checks the broker stop of every live OPEN position. A stop that filled at the
     * broker is booked as an exit; without this the position would stay OPEN locally
     * and a later exit order would open a reverse position. Also re-establishes
     * protection that never completed after entry, and confirms stop state after an
     * interrupted modification. Never submits an exit order.
     */
    public Mono<Void> reconcileProtectiveStopsAtRuntime() {
        if (!liveEnabled) {
            return Mono.empty();
        }
        return getOpenTrades()
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .concatMap(trade -> withPositionLock(trade.getSignalId(),
                        () -> reconcileProtectiveStop(trade),
                        Mono::<Void>empty)
                        .onErrorResume(error -> {
                            log.error("PROTECTIVE STOP RECONCILIATION FAILED | SignalId={} | StopOrderId={} | Reason={}",
                                    trade.getSignalId(), trade.getProtectiveOrderId(), safeMessage(error), error);
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> reconcileProtectiveStop(ExecutedTradeRecord trade) {
        int remaining = remainingQuantity(trade);
        if (remaining <= 0 || !"OPEN".equalsIgnoreCase(trade.getStatus())) {
            return Mono.empty();
        }
        Long signalId = trade.getSignalId();
        String orderId = trade.getProtectiveOrderId();
        String status = trade.getProtectiveOrderStatus();

        if (isBlank(orderId)) {
            if (isBlank(status)) {
                log.warn("LIVE POSITION HAS NO BROKER STOP; PLACING PROTECTION | SignalId={} | Symbol={} | Qty={}",
                        signalId, trade.getSymbol(), remaining);
                return ensureProtectiveStop(trade).then();
            }
            if ("SUBMISSION_OUTCOME_UNKNOWN".equalsIgnoreCase(status)) {
                String tag = isBlank(trade.getProtectiveOrderTag())
                        ? protectiveTag(signalId) : trade.getProtectiveOrderTag();
                return brokerExecutionClient.findOrderByTag(tag)
                        .flatMap(history -> isBlank(history.getOrderId())
                                ? Mono.error(new IllegalStateException(
                                "Protective stop tag " + tag + " matched an order without an ID"))
                                : Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                        signalId, history.getOrderId(), "RECONCILING", null, null, tag))
                                .subscribeOn(Schedulers.boundedElastic()))
                        .doOnNext(saved -> log.warn("PROTECTIVE STOP RECOVERED BY TAG | SignalId={} | OrderId={}",
                                signalId, saved.getProtectiveOrderId()))
                        .switchIfEmpty(Mono.fromRunnable(() -> log.error(
                                "LIVE POSITION UNPROTECTED: stop submission outcome unknown and no order found for tag {} | SignalId={}",
                                tag, signalId)))
                        .then();
            }
            log.error("LIVE POSITION UNPROTECTED AT BROKER | SignalId={} | Symbol={} | Qty={} | StopState={}",
                    signalId, trade.getSymbol(), remaining, status);
            return Mono.empty();
        }

        if ("FILL_APPLIED".equalsIgnoreCase(status)) {
            log.error("LIVE POSITION UNPROTECTED AFTER PARTIAL STOP FILL | SignalId={} | Remaining={}",
                    signalId, remaining);
            return Mono.empty();
        }

        return brokerExecutionClient.getOrderDetails(orderId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No broker details for protective order " + orderId)))
                .flatMap(details -> {
                    String brokerStatus = normalizeStatus(details.getStatus());
                    if (isTerminalStatus(brokerStatus)) {
                        return settleTerminalProtectiveStop(trade, orderId, details)
                                .doOnNext(release -> {
                                    if (release.trade() != null && valueOrZero(details.getFilledQuantity()) == 0) {
                                        log.error("BROKER STOP NO LONGER ACTIVE; POSITION UNPROTECTED | SignalId={} | OrderId={} | BrokerStatus={} | Reason={}",
                                                signalId, orderId, brokerStatus,
                                                firstNonBlank(details.getStatusMessage(), details.getStatusMessageRaw(), "n/a"));
                                    }
                                })
                                .then();
                    }
                    if (!isWorkingProtectiveStatus(details.getStatus())) {
                        // Transient broker state (e.g. modify/cancel pending): check next cycle.
                        return Mono.empty();
                    }
                    boolean quantityMatches = details.getQuantity() != null && details.getQuantity() == remaining;
                    BigDecimal brokerTrigger = details.getTriggerPrice();
                    if (!quantityMatches || brokerTrigger == null) {
                        log.error("BROKER STOP DOES NOT MATCH POSITION | SignalId={} | OrderId={} | StopQty={} | Remaining={}",
                                signalId, orderId, details.getQuantity(), remaining);
                        return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                        signalId, orderId, "RECONCILIATION_REQUIRED", null, null, null))
                                .subscribeOn(Schedulers.boundedElastic())
                                .then();
                    }
                    boolean alreadyConfirmed = "ACTIVE".equalsIgnoreCase(status)
                            && trade.getProtectiveTriggerPrice() != null
                            && trade.getProtectiveTriggerPrice().compareTo(brokerTrigger) == 0;
                    if (alreadyConfirmed) {
                        return Mono.empty();
                    }
                    log.info("BROKER STOP CONFIRMED ACTIVE | SignalId={} | OrderId={} | Qty={} | Trigger={} | PreviousState={}",
                            signalId, orderId, remaining, brokerTrigger, status);
                    return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                    signalId, orderId, "ACTIVE", remaining, brokerTrigger, null))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then();
                });
    }

    /**
     * Retires positions and claims left active by an earlier trading session. Their
     * intraday exposure was squared off by the broker, and broker order APIs no
     * longer return those orders, so they can neither be reconciled nor exited.
     */
    public Mono<Void> expirePriorSessionState() {
        return Mono.fromRunnable(() -> {
                    LocalDateTime sessionStart = riskGuard.sessionStart();
                    for (ExecutedTradeRecord trade : executedTradeRepository.expirePriorSessionTrades(sessionStart)) {
                        log.error("PRIOR-SESSION POSITION EXPIRED | SignalId={} | Symbol={} | PreviousStatus={} | Remaining={} "
                                        + "| EntryOrderId={} | ExitOrderId={} | StopOrderId={} | Verify the outcome in the broker contract note",
                                trade.getSignalId(), trade.getSymbol(), trade.getStatus(), trade.getRemainingQuantity(),
                                trade.getEntryOrderId(), trade.getExitOrderId(), trade.getProtectiveOrderId());
                    }
                    List<Long> claims = idempotencyRepository.expirePriorSessionClaims(sessionStart);
                    if (!claims.isEmpty()) {
                        log.error("PRIOR-SESSION EXECUTION CLAIMS EXPIRED | SignalIds={} | Verify these orders in the broker contract note",
                                claims);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * On startup, recover entry orders that were deliberately kept in
     * ENTRY_PENDING because their partial fills were observed while the broker
     * order was still working. A terminal broker status promotes confirmed
     * exposure to OPEN; a non-terminal order is cancelled and verified before
     * it can become exit-eligible.
     */
    public Mono<Void> recoverPendingEntriesAfterStartup() {
        return Mono.fromCallable(executedTradeRepository::findEntryPendingTrades)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .concatMap(trade -> {
                    if (trade == null || trade.getSignalId() == null
                            || isBlank(trade.getEntryOrderId())) {
                        log.error("Invalid ENTRY_PENDING trade during startup recovery");
                        return Mono.error(new IllegalStateException(
                                "Invalid ENTRY_PENDING record; startup recovery cannot be certified"));
                    }

                    TradeExecutionRequest request = TradeExecutionRequest.builder()
                            .signalId(trade.getSignalId())
                            .symbol(trade.getSymbol())
                            .instrumentKey(trade.getInstrumentKey())
                            .signal(trade.getSide())
                            .quantity(trade.getRequestedQuantity())
                            .stopLoss(trade.getInitialStopLoss())
                            .target(trade.getTargetPrice())
                            .entryPrice(trade.getAverageEntryPrice())
                            .orderType("MARKET")
                            .build();

                    String orderId = trade.getEntryOrderId();
                    return brokerExecutionClient.getOrderDetails(orderId)
                            .flatMap(details -> {
                                String status = normalizeStatus(details.getStatus());
                                if (isComplete(status) || isRejectedOrCancelled(status)) {
                                    return reconcileDetails(request, orderId, details, 1).then();
                                }
                                log.warn("ENTRY_PENDING ORDER STILL WORKING AT STARTUP | SignalId={} | OrderId={} | Status={}",
                                        trade.getSignalId(), orderId, status);
                                return cancelAndReconcileEntry(request, orderId, 1).then();
                            })
                            .switchIfEmpty(Mono.defer(() -> Mono.error(new IllegalStateException(
                                    "Empty broker details during entry recovery"))))
                            .onErrorResume(error -> {
                                log.error("ENTRY STARTUP RECOVERY FAILED | SignalId={} | OrderId={} | Reason={}",
                                        trade.getSignalId(), orderId, safeMessage(error), error);
                                return Mono.error(new IllegalStateException(
                                        "Entry startup recovery failed for signalId=" + trade.getSignalId(),
                                        error));
                            });
                })
                .then()
                .doOnError(error -> log.error("Could not load ENTRY_PENDING trades during startup recovery", error));
    }

    /**
     * Reconcile an unresolved idempotency claim discovered during startup.
     * This method never places a replacement order. If the original order cannot
     * be identified and reconciled, it errors so the readiness gate stays closed.
     */
    public Mono<TradeExecutionResponse> recoverUnresolvedClaim(ExecutionIdempotencyRecord record) {
        if (record == null || record.getSignalId() == null
                || isBlank(record.getSymbol()) || isBlank(record.getSignal())
                || record.getQuantity() == null || record.getQuantity() <= 0
                || isBlank(record.getInstrumentKey()) || record.getEntryPrice() == null
                || record.getEntryPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new IllegalStateException(
                    "Unresolved idempotency claim lacks recovery metadata; signalId="
                            + (record == null ? null : record.getSignalId())
                            + ". Apply the metadata migration and manually reconcile legacy claims."));
        }

        TradeExecutionRequest request = TradeExecutionRequest.builder()
                .signalId(record.getSignalId())
                .symbol(record.getSymbol())
                .instrumentKey(record.getInstrumentKey())
                .signal(record.getSignal())
                .quantity(record.getQuantity())
                .entryPrice(record.getEntryPrice())
                .requiredMargin(record.getRequiredMargin())
                .stopLoss(record.getStopLoss())
                .target(record.getTarget())
                .orderType("MARKET")
                .build();

        if (!isBlank(record.getOrderId())) {
            return recoverExistingExecution(request, record)
                    .flatMap(response -> isRecoveryTerminalOrPersisted(response)
                            || ("PARTIAL_FILLED".equalsIgnoreCase(record.getStatus())
                            && response != null && "PENDING".equalsIgnoreCase(response.getStatus()))
                            ? Mono.just(response)
                            : Mono.error(new IllegalStateException(
                            "Existing broker order remains unresolved. signalId="
                            + record.getSignalId() + ", orderId=" + record.getOrderId())));
        }

        return brokerExecutionClient.findOrderByTag(record.getTag())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No broker order was confirmed for unresolved claim. Do not resubmit automatically. signalId="
                                + record.getSignalId() + ", tag=" + record.getTag())))
                .flatMap(existing -> recoverExistingBrokerOrder(request, existing))
                .flatMap(response -> isRecoveryTerminalOrPersisted(response)
                        || ("PARTIAL_FILLED".equalsIgnoreCase(record.getStatus())
                        && response != null && "PENDING".equalsIgnoreCase(response.getStatus()))
                        ? Mono.just(response)
                        : Mono.error(new IllegalStateException(
                        "Broker order remains unresolved for signalId=" + record.getSignalId())));
    }

    private boolean isRecoveryTerminalOrPersisted(TradeExecutionResponse response) {
        if (response == null) return false;
        String status = response.getStatus() == null ? "" : response.getStatus().toUpperCase();
        return "FILLED".equals(status) || "OPEN".equals(status)
                || "REJECTED".equals(status) || "CANCELLED".equals(status)
                || "CLOSED".equals(status);
    }

    @Override
    public Mono<TradeExecutionResponse> executeTrade(
            TradeExecutionRequest request) {

        if (!executionReadiness.isReady()) {
            ExecutionReadiness.Snapshot snapshot = executionReadiness.snapshot();
            String status = snapshot.state() == ExecutionReadiness.State.FAILED
                    ? "RECOVERY_FAILED" : "SERVICE_RECOVERING";
            return Mono.just(failure(request, status,
                    "Execution is blocked until startup reconciliation is complete. State="
                            + snapshot.state() + ", reason=" + snapshot.reason()));
        }

        alignProtectivePrices(request);

        String validationError = validate(request);

        if (validationError != null) {
            return Mono.just(
                    failure(request, "VALIDATION_FAILED", validationError));
        }

        String tag = buildExecutionTag(request);
        String orderType = normalizeOrderType(request.getOrderType());

        log.info(
                "TRADE EXECUTION STARTED | SignalId={} | Symbol={} | Signal={} | Qty={} | OrderType={} | Tag={}",
                request.getSignalId(),
                request.getSymbol(),
                normalizeSignal(request.getSignal()),
                request.getQuantity(),
                orderType,
                tag
        );

        // Step 1: Search broker history for an existing order.
        // If the lookup errors, the outer error handler prevents new submission.
        return brokerExecutionClient.findOrderByTag(tag)
                .flatMap(existing ->
                        recoverExistingBrokerOrder(request, existing)
                                .switchIfEmpty(Mono.just(
                                        pending(
                                                request,
                                                existing.getOrderId(),
                                                "Existing broker order was found, but recovery "
                                                        + "returned no result. No new order was "
                                                        + "placed; reconciliation is required."
                                        )
                                ))
                )
                .switchIfEmpty(Mono.defer(() ->

                        // Step 2: No matching broker order was returned.
                        // Check for an existing idempotency record before claiming
                        // or submitting another order.
                        idempotencyRepository.find(request.getSignalId())
                                .flatMap(record -> {

                                    if (!matchesExecutionClaim(request, record)) {
                                        return Mono.just(failure(
                                                request,
                                                "IDEMPOTENCY_CONFLICT",
                                                "An existing execution record does not match "
                                                        + "this request. No new order was "
                                                        + "submitted; manual reconciliation "
                                                        + "is required."
                                        ));
                                    }

                                    // Existing claim: recover it, never submit a
                                    // replacement merely because broker history
                                    // returned no matching order.
                                    return recoverExistingExecution(request, record)
                                            .switchIfEmpty(Mono.just(
                                                    pending(
                                                            request,
                                                            record.getOrderId(),
                                                            "An existing execution record was found, "
                                                                    + "but recovery returned no result. "
                                                                    + "No new order was placed; "
                                                                    + "reconciliation is required."
                                                    )
                                            ));
                                })

                                // Step 3: Only when no existing claim exists,
                                // let claimAndExecute perform the atomic claim.
                                .switchIfEmpty(Mono.defer(() ->
                                        claimAndExecute(request, tag, orderType)
                                ))
                ))
                .onErrorResume(error -> {
                    log.error(
                            "TRADE EXECUTION ABORTED SAFELY | SignalId={} | Symbol={} | Reason={}",
                            request.getSignalId(),
                            request.getSymbol(),
                            safeMessage(error),
                            error
                    );

                    return Mono.just(failure(
                            request,
                            "EXECUTION_CHECK_FAILED",
                            "Execution could not be safely confirmed. Check broker "
                                    + "history and the idempotency record before retrying: "
                                    + safeMessage(error)
                    ));
                });
    }

    /**
     * Returns persisted OPEN positions for read-only exit evaluation.
     * JdbcTemplate is blocking, so repository access runs on boundedElastic.
     */
    @Override
    public Mono<java.util.List<ExecutedTradeRecord>> getOpenTrades() {
        // Rows from an earlier session have no broker exposure (intraday product is
        // squared off by the broker); exiting them would open a fresh position.
        return Mono.fromCallable(executedTradeRepository::findOpenTrades)
                .subscribeOn(Schedulers.boundedElastic())
                .map(trades -> trades.stream()
                        .filter(trade -> !riskGuard.isPriorSession(trade.getOpenedAt()))
                        .toList());
    }

    @Override
    public Mono<java.util.List<ExecutedTradeRecord>> getSessionTrades() {
        return Mono.fromCallable(() -> executedTradeRepository.findTradesOpenedSince(riskGuard.sessionStart()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ExecutedTradeRecord> updateProtection(TradeProtectionUpdateRequest request) {
        if (!executionReadiness.isReady()) {
            ExecutionReadiness.Snapshot snapshot = executionReadiness.snapshot();
            return Mono.error(new IllegalStateException(
                    "Protection updates are blocked until startup recovery is complete. State="
                            + snapshot.state() + ", reason=" + snapshot.reason()));
        }
        if (request == null || request.signalId() == null
                || (request.stopLoss() == null && request.targetPrice() == null)) {
            return Mono.error(new IllegalArgumentException(
                    "signalId and at least one of stopLoss or targetPrice are required"));
        }
        if (Boolean.TRUE.equals(request.targetExtended()) && request.targetPrice() == null) {
            return Mono.error(new IllegalArgumentException(
                    "targetPrice is required when marking a target extension"));
        }
        return Mono.fromCallable(() -> executedTradeRepository.findBySignalId(request.signalId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Executed trade not found for signalId=" + request.signalId())))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(existing -> applyProtectionUpdate(
                        existing, alignProtectivePrices(request, existing.getSide())));
    }

    private Mono<ExecutedTradeRecord> applyProtectionUpdate(
            ExecutedTradeRecord existing, TradeProtectionUpdateRequest request) {
        // Target-only changes do not require a broker stop modification.
        if (request.stopLoss() == null) {
            return persistProtectionLevels(request);
        }

        String protectiveOrderId = existing.getProtectiveOrderId();
        BigDecimal knownBrokerTrigger = existing.getProtectiveTriggerPrice();
        if (isBlank(protectiveOrderId)) {
            // Paper mode can continue to update its simulated stop. In live mode,
            // never update only the DB when a filled position has no broker stop.
            if (liveEnabled && isOpenPosition(existing)) {
                return Mono.error(new IllegalStateException(
                        "Cannot update stop-loss: no broker protective order is recorded. "
                                + "Reconcile protection before changing the stop. SignalId="
                                + request.signalId()));
            }
            return persistProtectionLevels(request);
        }

        // The persisted trigger is authoritative only while the stop is confirmed ACTIVE.
        if ("ACTIVE".equalsIgnoreCase(existing.getProtectiveOrderStatus())
                && knownBrokerTrigger != null
                && request.stopLoss().compareTo(knownBrokerTrigger) == 0) {
            return persistProtectionLevels(request);
        }
        if (!liveEnabled) {
            return Mono.error(new IllegalStateException(
                    "Live execution is disabled; broker stop was not modified and database stop-loss was not changed."));
        }
        return withPositionLock(existing.getSignalId(),
                () -> modifyAndVerifyProtectiveStop(existing, request),
                () -> Mono.error(new IllegalStateException(
                        "Another protective-stop or exit operation is in progress; stop-loss was not changed. SignalId="
                                + request.signalId())));
    }

    private Mono<ExecutedTradeRecord> persistProtectionLevels(TradeProtectionUpdateRequest request) {
        return Mono.fromCallable(() -> executedTradeRepository.updateProtectionLevels(
                        request.signalId(), request.stopLoss(), request.targetPrice(), request.targetExtended()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Modify the already-persisted broker stop, verify the broker's current order details,
     * and only then persist the new application protection levels. A timeout or mismatch
     * leaves the stop in RECONCILIATION_REQUIRED and is never treated as success.
     */
    private Mono<ExecutedTradeRecord> modifyAndVerifyProtectiveStop(
            ExecutedTradeRecord trade, TradeProtectionUpdateRequest update) {
        Long signalId = trade.getSignalId();
        String orderId = trade.getProtectiveOrderId();
        int remaining = trade.getRemainingQuantity() == null
                ? Math.max(0, valueOrZero(trade.getFilledQuantity()) - valueOrZero(trade.getExitFilledQuantity()))
                : trade.getRemainingQuantity();
        Integer protectedQuantity = trade.getProtectiveOrderQuantity();
        BigDecimal stop = update.stopLoss();
        String type = protectiveStopOrderType == null ? "SL-M" : protectiveStopOrderType.trim().toUpperCase();

        if (remaining <= 0 || protectedQuantity == null || protectedQuantity <= 0
                || protectedQuantity != remaining) {
            return Mono.error(new IllegalStateException(
                    "Cannot modify protective stop because broker-protected quantity does not match remaining position. "
                            + "SignalId=" + signalId + ", protected=" + protectedQuantity + ", remaining=" + remaining));
        }
        if (isBlank(trade.getInstrumentKey()) || isBlank(trade.getSymbol())) {
            return Mono.error(new IllegalStateException(
                    "Cannot modify protective stop without persisted symbol and instrument key. SignalId=" + signalId));
        }
        if (!"SL".equals(type) && !"SL-M".equals(type)) {
            return Mono.error(new IllegalStateException("execution.protection.order-type must be SL or SL-M"));
        }

        String exitSide = "BUY".equalsIgnoreCase(trade.getSide()) ? "SELL" : "BUY";
        BrokerOrderRequest brokerRequest = BrokerOrderRequest.builder()
                .symbol(trade.getSymbol())
                .instrumentKey(trade.getInstrumentKey())
                .signal(exitSide)
                .transactionType(exitSide)
                .quantity(remaining)
                .triggerPrice(stop)
                .price("SL".equals(type) ? stop : BigDecimal.ZERO)
                .orderType(type)
                .product("I")
                .validity("DAY")
                .tag(isBlank(trade.getProtectiveOrderTag()) ? protectiveTag(signalId) : trade.getProtectiveOrderTag())
                .build();

        String tag = brokerRequest.getTag();
        return Mono.fromCallable(() -> executedTradeRepository.claimProtectiveModification(signalId, orderId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(claimed -> {
                    if (!claimed) {
                        return Mono.error(new IllegalStateException(
                                "Protective stop is not confirmed ACTIVE (status=" + trade.getProtectiveOrderStatus()
                                        + ") or a modification is already in flight; stop-loss was not changed. SignalId="
                                        + signalId));
                    }
                    return brokerExecutionClient.modifyOrder(orderId, brokerRequest)
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "Empty broker response while modifying protective stop. SignalId=" + signalId)))
                            .flatMap(response -> {
                                if (response == null || !response.isSuccess()
                                        || isBlank(response.getOrderId()) || !orderId.equals(response.getOrderId())) {
                                    return Mono.error(new IllegalStateException(
                                            "Broker did not confirm protective-stop modification for order " + orderId
                                                    + "; outcome must be reconciled before retry."));
                                }
                                return awaitModifiedStop(orderId, remaining, stop, 1);
                            })
                            .flatMap(details -> persistProtectionLevels(update)
                                    .flatMap(saved -> Mono.fromCallable(() ->
                                                    executedTradeRepository.updateProtectiveOrderState(
                                                            signalId, orderId, "ACTIVE", remaining, stop, tag))
                                            .subscribeOn(Schedulers.boundedElastic())))
                            // Keep the last confirmed quantity/trigger: the requested values
                            // are unconfirmed and must not be mistaken for broker state.
                            .onErrorResume(error -> Mono.fromCallable(() ->
                                            executedTradeRepository.updateProtectiveOrderState(
                                                    signalId, orderId, "RECONCILIATION_REQUIRED", null, null, null))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .onErrorResume(stateError -> {
                                        log.error("Could not persist protective-stop reconciliation state | SignalId={} | OrderId={}",
                                                signalId, orderId, stateError);
                                        return Mono.empty();
                                    })
                                    .then(Mono.error(new IllegalStateException(
                                            "Protective-stop modification is not confirmed. Reconcile broker order before retrying. SignalId="
                                                    + signalId, error))));
                });
    }

    /**
     * Polls the broker until a modified stop shows the requested quantity and trigger
     * while still working. Upstox passes through transient states such as
     * "modify pending" before the new trigger is visible.
     */
    private Mono<UpstoxOrderDetailsResponse.DataResponse> awaitModifiedStop(
            String orderId, int quantity, BigDecimal stop, int attempt) {
        return brokerExecutionClient.getOrderDetails(orderId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No broker details after protective-stop modification. OrderId=" + orderId)))
                .flatMap(details -> {
                    String status = details.getStatus() == null ? "UNKNOWN" : details.getStatus().trim();
                    boolean quantityMatches = details.getQuantity() != null && details.getQuantity() == quantity;
                    boolean triggerMatches = details.getTriggerPrice() != null
                            && details.getTriggerPrice().compareTo(stop) == 0;
                    if (isWorkingProtectiveStatus(status) && quantityMatches && triggerMatches) {
                        return Mono.just(details);
                    }
                    if (isTerminalStatus(normalizeStatus(status)) || attempt >= reconciliationMaxAttempts) {
                        return Mono.error(new IllegalStateException(
                                "Modified broker stop is not confirmed active/matching. OrderId=" + orderId
                                        + ", status=" + status + ", quantity=" + details.getQuantity()
                                        + ", trigger=" + details.getTriggerPrice()));
                    }
                    return Mono.delay(Duration.ofMillis(reconciliationIntervalMs))
                            .then(Mono.defer(() -> awaitModifiedStop(orderId, quantity, stop, attempt + 1)));
                });
    }

    private boolean isOpenPosition(ExecutedTradeRecord trade) {
        return trade != null && ("OPEN".equalsIgnoreCase(trade.getStatus())
                || "PARTIALLY_EXITED".equalsIgnoreCase(trade.getStatus()));
    }

    @Override
    public Mono<TradeExecutionResponse> exitTrade(TradeExitRequest request) {

        if (!executionReadiness.isReady()) {
            ExecutionReadiness.Snapshot snapshot = executionReadiness.snapshot();
            String status = snapshot.state() == ExecutionReadiness.State.FAILED
                    ? "RECOVERY_FAILED" : "SERVICE_RECOVERING";
            return Mono.just(exitFailure(request, status,
                    "Exit requests are blocked until startup reconciliation is complete. State="
                            + snapshot.state() + ", reason=" + snapshot.reason()));
        }

        if (request == null || request.getSignalId() == null) {
            return Mono.just(exitFailure(request, "VALIDATION_FAILED",
                    "signalId is required"));
        }

        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            return Mono.just(exitFailure(request, "VALIDATION_FAILED",
                    "quantity must be greater than zero"));
        }

        String orderType = normalizeOrderType(request.getOrderType());

        if (!"MARKET".equals(orderType)) {
            return Mono.just(exitFailure(request, "VALIDATION_FAILED",
                    "Exit currently supports MARKET orders only because TradeExitRequest has no limit or trigger price fields"));
        }

        // Do not reject the whole request here when live execution is disabled.
        // An already-persisted EXIT_PENDING order must still be reconciled using
        // read-only broker lookups. The live-enabled guard is applied below only
        // after the trade is confirmed OPEN and before any new exit claim/order.
        return Mono.fromCallable(() ->
                        executedTradeRepository.findBySignalId(request.getSignalId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Executed trade not found for signalId="
                                                + request.getSignalId())))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(trade -> {

                    boolean exitAlreadyPending =
                            "EXIT_PENDING".equalsIgnoreCase(trade.getStatus());

                    int remaining = trade.getRemainingQuantity() == null
                            ? Math.max(0,
                            valueOrZero(trade.getFilledQuantity())
                            - valueOrZero(trade.getExitFilledQuantity()))
                            : trade.getRemainingQuantity();

                    if (remaining <= 0 || "CLOSED".equalsIgnoreCase(trade.getStatus())) {
                        return Mono.just(exitFailure(request, "TRADE_NOT_OPEN",
                                "Trade has no remaining quantity to exit"));
                    }

                    // A pending order must be reconciled using its original persisted
                    // quantity. Do not reject its recovery because a retry request has
                    // a different quantity or because remaining quantity has changed.
                    if (!exitAlreadyPending && request.getQuantity() > remaining) {
                        return Mono.just(exitFailure(request, "INVALID_EXIT_QUANTITY",
                                "Requested exit quantity exceeds remaining quantity=" + remaining));
                    }

                    if (isBlank(trade.getInstrumentKey())
                            || isBlank(trade.getSide())
                            || trade.getAverageEntryPrice() == null
                            || trade.getAverageEntryPrice().compareTo(BigDecimal.ZERO) <= 0) {
                        return Mono.just(exitFailure(request, "TRADE_DATA_INVALID",
                                "Persisted trade is missing instrument, side or entry-price data; manual reconciliation required"));
                    }

                    int priorExitFilled = valueOrZero(trade.getExitFilledQuantity());

                    Integer tagQuantity = exitAlreadyPending
                            ? trade.getExitOrderQuantity()
                            : request.getQuantity();

                    if (tagQuantity == null || tagQuantity <= 0) {
                        return Mono.just(exitPending(
                                request,
                                trade.getExitOrderId(),
                                "Cannot safely reconstruct the exit-order tag because "
                                        + "the original submitted quantity is missing. "
                                        + "Manual broker reconciliation is required."
                        ));
                    }

                    String tag = buildExitTag(
                            trade.getSignalId(),
                            trade.getExitAttempt(),
                            priorExitFilled,
                            tagQuantity
                    );

                    /*
                     * An existing EXIT_PENDING trade must be reconciled using its
                     * persisted broker order ID. If that ID is missing, search only
                     * by the deterministic original tag; never submit a replacement.
                     */
                    if (exitAlreadyPending) {
                        String persistedExitOrderId = trade.getExitOrderId();

                        TradeExitRequest recoveryRequest = TradeExitRequest.builder()
                                .signalId(trade.getSignalId())
                                .quantity(tagQuantity)
                                .orderType("MARKET")
                                .build();

                        if (isBlank(persistedExitOrderId)) {
                            log.warn(
                                    "EXIT RECOVERY BY TAG STARTED | SignalId={} | Tag={}",
                                    trade.getSignalId(),
                                    tag
                            );

                            return brokerExecutionClient.findOrderByTag(tag)
                                    .flatMap(existing -> {
                                        String recoveredOrderId = existing.getOrderId();

                                        if (isBlank(recoveredOrderId)) {
                                            return Mono.just(exitPending(
                                                    recoveryRequest,
                                                    null,
                                                    "Broker history matched an exit order but returned "
                                                            + "no order ID. Trade remains EXIT_PENDING; "
                                                            + "manual reconciliation is required. "
                                                            + "No replacement order was submitted."
                                            ));
                                        }

                                        log.info(
                                                "EXIT ORDER ID RECOVERED | SignalId={} | OrderId={} | Tag={}",
                                                trade.getSignalId(),
                                                recoveredOrderId,
                                                tag
                                        );

                                        return persistExitOrderId(
                                                trade,
                                                recoveredOrderId,
                                                tagQuantity
                                        ).then(Mono.defer(() ->
                                                reconcileExitOrder(
                                                        recoveryRequest,
                                                        trade,
                                                        recoveredOrderId,
                                                        0
                                                )
                                        )).onErrorResume(error -> {
                                            log.error(
                                                    "EXIT ORDER RECOVERY FAILED | SignalId={} | OrderId={} | Reason={}",
                                                    trade.getSignalId(),
                                                    recoveredOrderId,
                                                    safeMessage(error),
                                                    error
                                            );

                                            return Mono.just(exitPending(
                                                    recoveryRequest,
                                                    recoveredOrderId,
                                                    "Existing broker order was found, but persistence "
                                                            + "or reconciliation failed. No replacement "
                                                            + "order was submitted. Reason: "
                                                            + safeMessage(error)
                                            ));
                                        });
                                    })
                                    .switchIfEmpty(Mono.just(exitPending(
                                            recoveryRequest,
                                            null,
                                            "No matching exit order was returned by broker history. "
                                                    + "The original placement outcome remains unconfirmed. "
                                                    + "Trade remains EXIT_PENDING; no replacement order "
                                                    + "was submitted."
                                    )))
                                    .onErrorResume(error -> {
                                        log.error(
                                                "EXIT TAG LOOKUP FAILED | SignalId={} | Tag={} | Reason={}",
                                                trade.getSignalId(),
                                                tag,
                                                safeMessage(error),
                                                error
                                        );

                                        return Mono.just(exitPending(
                                                recoveryRequest,
                                                null,
                                                "Could not confirm the existing exit order using broker "
                                                        + "history. Trade remains EXIT_PENDING; no replacement "
                                                        + "order was submitted. Reason: "
                                                        + safeMessage(error)
                                        ));
                                    });
                        }

                        return reconcileExitOrder(
                                recoveryRequest,
                                trade,
                                persistedExitOrderId,
                                0
                        ).onErrorResume(error -> {
                            log.error(
                                    "PERSISTED EXIT ORDER RECONCILIATION FAILED | SignalId={} | OrderId={} | Reason={}",
                                    trade.getSignalId(),
                                    persistedExitOrderId,
                                    safeMessage(error),
                                    error
                            );

                            return Mono.just(exitPending(
                                    recoveryRequest,
                                    persistedExitOrderId,
                                    "Persisted exit order reconciliation failed. "
                                            + "No duplicate order was submitted: "
                                            + safeMessage(error)
                            ));
                        });
                    }

                    if (!"OPEN".equalsIgnoreCase(trade.getStatus())) {
                        return Mono.just(exitFailure(request, "TRADE_NOT_OPEN",
                                "Trade status is " + trade.getStatus() + "; exit is not allowed"));
                    }

                    // Safety barrier: existing EXIT_PENDING orders above may be
                    // reconciled while live execution is disabled, but a new exit
                    // order must never be claimed or submitted unless explicitly enabled.
                    if (!liveEnabled) {
                        return Mono.just(exitFailure(request, "LIVE_DISABLED",
                                "Live broker execution is disabled; no exit order was submitted and trade state was not changed"));
                    }

                    // The broker has already squared off an earlier session's intraday
                    // position; a new exit order would open a fresh position instead.
                    if (riskGuard.isPriorSession(trade.getOpenedAt())) {
                        return Mono.just(exitFailure(request, "STALE_POSITION",
                                "Position was opened in an earlier trading session and has no broker exposure; "
                                        + "no exit order was submitted. Reconcile it manually."));
                    }

                    // A live market exit must not race an existing broker-side stop:
                    // if both filled, the position would reverse. Cancel the stop and
                    // book any stop fill first, then exit only what is still open.
                    return withPositionLock(trade.getSignalId(),
                            () -> releaseProtectiveStopBeforeExit(trade)
                                    .onErrorResume(error -> {
                                        log.error("EXIT BLOCKED: PROTECTIVE STOP NOT CONFIRMED CANCELLED | SignalId={} | StopOrderId={} | Reason={}",
                                                trade.getSignalId(), trade.getProtectiveOrderId(), safeMessage(error), error);
                                        return Mono.just(ProtectiveRelease.done(exitFailure(request, "EXIT_BLOCKED",
                                                "The broker protective stop could not be confirmed cancelled, so no market exit "
                                                        + "was submitted and trade state was not changed. Retry the exit; reason: "
                                                        + safeMessage(error))));
                                    })
                                    .flatMap(release -> release.response() != null
                                            ? Mono.just(release.response())
                                            : claimAndSubmitExit(request, release.trade(), orderType)),
                            () -> Mono.just(exitFailure(request, "EXIT_BLOCKED",
                                    "Another protective-stop or exit operation is in progress for this position; "
                                            + "no order was submitted. Retry the exit.")));
                })
                .onErrorResume(error -> {
                    log.error(
                            "EXIT CHECK FAILED | SignalId={} | Reason={}",
                            request.getSignalId(),
                            safeMessage(error),
                            error
                    );

                    return Mono.just(exitFailure(
                            request,
                            "EXIT_CHECK_FAILED",
                            "Exit could not be safely processed: " + safeMessage(error)
                    ));
                });
    }

    /**
     * Atomically claims an OPEN trade for exit, then checks broker history for the
     * attempt tag before submitting. The trade must reflect any protective-stop fill
     * already booked, so the exit never sells more than is still held.
     */
    private Mono<TradeExecutionResponse> claimAndSubmitExit(
            TradeExitRequest originalRequest,
            ExecutedTradeRecord trade,
            String orderType) {

        int remaining = remainingQuantity(trade);
        int priorExitFilled = valueOrZero(trade.getExitFilledQuantity());
        if (remaining <= 0 || !"OPEN".equalsIgnoreCase(trade.getStatus())) {
            return Mono.just(exitFailure(originalRequest, "TRADE_NOT_OPEN",
                    "Trade has no remaining quantity to exit; status=" + trade.getStatus()));
        }

        final TradeExitRequest request;
        if (originalRequest.getQuantity() > remaining) {
            // Only possible after a protective-stop partial fill was just booked.
            log.warn("EXIT QUANTITY REDUCED TO REMAINING POSITION | SignalId={} | Requested={} | Remaining={}",
                    trade.getSignalId(), originalRequest.getQuantity(), remaining);
            request = TradeExitRequest.builder()
                    .signalId(originalRequest.getSignalId())
                    .quantity(remaining)
                    .orderType(originalRequest.getOrderType())
                    .build();
        } else {
            request = originalRequest;
        }

        return Mono.fromCallable(() ->
                        executedTradeRepository.claimExit(request.getSignalId(), request.getQuantity()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(claimed -> {
                    if (!claimed) {
                        return Mono.just(exitPending(
                                request,
                                trade.getExitOrderId(),
                                "Another request claimed this trade for exit, "
                                        + "or the trade is no longer OPEN. "
                                        + "No new order was submitted."
                        ));
                    }

                    /*
                     * claimExit increments the persisted attempt number.
                     * Reload it before building the broker tag so a retry
                     * cannot reuse a previous rejected zero-fill attempt's tag.
                     */
                    return Mono.fromCallable(() ->
                                    executedTradeRepository.findBySignalId(request.getSignalId())
                                            .orElseThrow(() -> new IllegalStateException(
                                                    "Trade disappeared after exit claim: "
                                                            + request.getSignalId())))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(claimedTrade -> {
                                int claimedAttempt = valueOrZero(claimedTrade.getExitAttempt());
                                if (claimedAttempt <= 0) {
                                    return Mono.error(new IllegalStateException(
                                            "Exit claim did not persist a positive attempt number for signalId="
                                                    + request.getSignalId()));
                                }

                                String attemptTag = buildExitTag(
                                        claimedTrade.getSignalId(),
                                        claimedTrade.getExitAttempt(),
                                        priorExitFilled,
                                        request.getQuantity());

                                log.info("EXIT ATTEMPT CLAIMED | SignalId={} | Attempt={} | Tag={}",
                                        claimedTrade.getSignalId(), claimedAttempt, attemptTag);

                                /*
                                 * Existing order -> persist its ID and reconcile.
                                 * Confirmed empty lookup -> submit this attempt.
                                 * Lookup error -> remain pending; do not submit.
                                 */
                                return brokerExecutionClient.findOrderByTag(attemptTag)
                                        .flatMap(existing -> {
                                            if (isBlank(existing.getOrderId())) {
                                                return Mono.just(exitPending(
                                                        request,
                                                        null,
                                                        "Broker history returned an exit order without "
                                                                + "an order ID. Trade remains EXIT_PENDING; "
                                                                + "manual reconciliation is required. "
                                                                + "No replacement order was submitted."));
                                            }

                                            return persistExitOrderId(
                                                    claimedTrade,
                                                    existing.getOrderId(),
                                                    request.getQuantity())
                                                    .then(Mono.defer(() -> reconcileExitOrder(
                                                            request,
                                                            claimedTrade,
                                                            existing.getOrderId(),
                                                            0)));
                                        })
                                        .switchIfEmpty(Mono.defer(() -> submitExitOrder(
                                                request,
                                                claimedTrade,
                                                remaining,
                                                orderType,
                                                attemptTag)))
                                        .onErrorResume(error -> {
                                            log.error(
                                                    "EXIT PROCESSING FAILED SAFELY | SignalId={} | Attempt={} | Reason={}",
                                                    request.getSignalId(), claimedAttempt,
                                                    safeMessage(error), error);

                                            return Mono.just(exitPending(
                                                    request,
                                                    claimedTrade.getExitOrderId(),
                                                    "Exit processing could not be safely confirmed. "
                                                            + "Trade remains EXIT_PENDING; reconcile broker "
                                                            + "history before retrying: "
                                                            + safeMessage(error)));
                                        });
                            });
                });
    }

    /**
     * Outcome of releasing the broker stop before a market exit: either the refreshed
     * trade a market exit may proceed with, or a final response (the stop already
     * closed the position, or the exit must not proceed).
     */
    private record ProtectiveRelease(ExecutedTradeRecord trade, TradeExecutionResponse response) {
        static ProtectiveRelease proceed(ExecutedTradeRecord trade) {
            return new ProtectiveRelease(trade, null);
        }

        static ProtectiveRelease done(TradeExecutionResponse response) {
            return new ProtectiveRelease(null, response);
        }
    }

    /**
     * Ensures no broker stop can still fill before a market exit is submitted.
     * A working stop is cancelled and its terminal state confirmed; any quantity it
     * filled is booked as a broker stop-loss exit. Errors mean the stop state is not
     * confirmed and the caller must not submit a market exit.
     */
    private Mono<ProtectiveRelease> releaseProtectiveStopBeforeExit(ExecutedTradeRecord trade) {
        String orderId = trade.getProtectiveOrderId();
        String status = trade.getProtectiveOrderStatus();

        if (!isBlank(orderId)) {
            return cancelAndSettleProtectiveStop(trade, orderId);
        }
        if (isBlank(status) || isProtectiveOrderTerminalWithoutFill(status)
                || "UNPROTECTED_NO_STOP_PRICE".equalsIgnoreCase(status)) {
            return Mono.just(ProtectiveRelease.proceed(trade));
        }

        // Submission outcome was never confirmed: the stop may exist under its tag.
        String tag = isBlank(trade.getProtectiveOrderTag())
                ? protectiveTag(trade.getSignalId()) : trade.getProtectiveOrderTag();
        return brokerExecutionClient.findOrderByTag(tag)
                .flatMap(history -> {
                    if (isBlank(history.getOrderId())) {
                        return Mono.error(new IllegalStateException(
                                "Protective stop tag " + tag + " matched an order without an ID"));
                    }
                    log.warn("PROTECTIVE STOP RECOVERED BY TAG BEFORE EXIT | SignalId={} | OrderId={} | Tag={}",
                            trade.getSignalId(), history.getOrderId(), tag);
                    return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                    trade.getSignalId(), history.getOrderId(), "RECONCILING", null, null, tag))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(saved -> cancelAndSettleProtectiveStop(saved, history.getOrderId()));
                })
                .switchIfEmpty(Mono.defer(() -> Mono.fromCallable(() ->
                                executedTradeRepository.updateProtectiveOrderState(
                                        trade.getSignalId(), null, "NOT_FOUND", null, null, null))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(ProtectiveRelease::proceed)));
    }

    private Mono<ProtectiveRelease> cancelAndSettleProtectiveStop(ExecutedTradeRecord trade, String orderId) {
        return brokerExecutionClient.getOrderDetails(orderId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No broker details for protective order " + orderId)))
                .flatMap(details -> {
                    if (isTerminalStatus(normalizeStatus(details.getStatus()))) {
                        return settleTerminalProtectiveStop(trade, orderId, details);
                    }
                    log.warn("CANCELLING PROTECTIVE STOP BEFORE MARKET EXIT | SignalId={} | OrderId={} | Status={}",
                            trade.getSignalId(), orderId, details.getStatus());
                    return brokerExecutionClient.cancelOrder(orderId)
                            .flatMap(acknowledged -> Boolean.TRUE.equals(acknowledged)
                                    ? awaitTerminalOrder(orderId, 1)
                                    : Mono.error(new IllegalStateException(
                                    "Broker did not acknowledge cancellation of protective order " + orderId)))
                            // A cancel can be refused because the stop just triggered;
                            // the broker's current state decides what happened.
                            .onErrorResume(cancelError -> {
                                log.warn("PROTECTIVE STOP CANCEL NOT CONFIRMED; RE-CHECKING BROKER STATE | OrderId={} | Reason={}",
                                        orderId, safeMessage(cancelError));
                                return awaitTerminalOrder(orderId, 1);
                            })
                            .flatMap(terminal -> settleTerminalProtectiveStop(trade, orderId, terminal));
                });
    }

    /** Polls order details until a terminal status; errors if still working after the window. */
    private Mono<UpstoxOrderDetailsResponse.DataResponse> awaitTerminalOrder(String orderId, int attempt) {
        return brokerExecutionClient.getOrderDetails(orderId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No broker details for order " + orderId)))
                .flatMap(details -> {
                    String status = normalizeStatus(details.getStatus());
                    if (isTerminalStatus(status)) {
                        return Mono.just(details);
                    }
                    if (attempt >= reconciliationMaxAttempts) {
                        return Mono.error(new IllegalStateException(
                                "Order " + orderId + " is still " + status + " after cancellation was requested"));
                    }
                    return Mono.delay(Duration.ofMillis(reconciliationIntervalMs))
                            .then(Mono.defer(() -> awaitTerminalOrder(orderId, attempt + 1)));
                });
    }

    private Mono<ProtectiveRelease> settleTerminalProtectiveStop(
            ExecutedTradeRecord trade,
            String orderId,
            UpstoxOrderDetailsResponse.DataResponse details) {

        String status = normalizeStatus(details.getStatus());
        Integer filled = details.getFilledQuantity();
        if (filled == null || filled < 0) {
            return Mono.error(new IllegalStateException(
                    "Terminal protective order " + orderId + " has no valid filled quantity"));
        }

        if (filled == 0) {
            if (isComplete(status)) {
                return Mono.error(new IllegalStateException(
                        "Protective order " + orderId + " reports complete with zero fill"));
            }
            String terminalState = "rejected".equals(status) ? "REJECTED"
                    : "expired".equals(status) ? "EXPIRED" : "CANCELLED";
            return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                            trade.getSignalId(), orderId, terminalState, null, null, null))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(ProtectiveRelease::proceed);
        }

        return Mono.fromCallable(() -> executedTradeRepository.applyProtectiveStopFill(
                        trade.getSignalId(), orderId, filled, details.getAveragePrice()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(saved -> {
                    log.warn("BROKER STOP-LOSS FILL BOOKED | SignalId={} | OrderId={} | FilledQty={} | AvgPrice={} | Status={} | Remaining={}",
                            saved.getSignalId(), orderId, filled, details.getAveragePrice(),
                            saved.getStatus(), saved.getRemainingQuantity());
                    if (!"CLOSED".equalsIgnoreCase(saved.getStatus())) {
                        return ProtectiveRelease.proceed(saved);
                    }
                    return ProtectiveRelease.done(TradeExecutionResponse.builder()
                            .success(true)
                            .signalId(saved.getSignalId())
                            .symbol(saved.getSymbol())
                            .signal("BUY".equalsIgnoreCase(saved.getSide()) ? "SELL" : "BUY")
                            .quantity(filled)
                            .filledQuantity(filled)
                            .executedPrice(saved.getExitAveragePrice())
                            .orderId(orderId)
                            .status("CLOSED")
                            .message("Position was closed by the broker stop-loss order; no market exit was submitted")
                            .build());
                });
    }

    /**
     * Runs {@code action} while holding the per-position lock, or {@code whenBusy}
     * if another protective-stop/exit operation for the same position is in flight.
     */
    private <T> Mono<T> withPositionLock(Long signalId, Supplier<Mono<T>> action, Supplier<Mono<T>> whenBusy) {
        return Mono.defer(() -> {
            if (signalId == null || !positionsInFlight.add(signalId)) {
                return whenBusy.get();
            }
            return Mono.defer(action).doFinally(signal -> positionsInFlight.remove(signalId));
        });
    }

    private int remainingQuantity(ExecutedTradeRecord trade) {
        return trade.getRemainingQuantity() == null
                ? Math.max(0, valueOrZero(trade.getFilledQuantity()) - valueOrZero(trade.getExitFilledQuantity()))
                : trade.getRemainingQuantity();
    }

    private Mono<TradeExecutionResponse> submitExitOrder(
            TradeExitRequest request,
            ExecutedTradeRecord trade,
            int remaining,
            String orderType,
            String tag) {

        if (request == null || trade == null || request.getSignalId() == null) {
            return Mono.just(exitFailure(
                    request, "VALIDATION_FAILED",
                    "Exit request, trade and signalId are required"));
        }

        Integer requestedQuantity = request.getQuantity();

        if (requestedQuantity == null
                || requestedQuantity <= 0
                || remaining <= 0
                || requestedQuantity > remaining) {
            return Mono.just(exitPending(
                    request, trade.getExitOrderId(),
                    "Invalid exit quantity. No broker order was submitted."));
        }

        if (!liveEnabled) {
            return Mono.just(exitFailure(
                    request, "LIVE_DISABLED",
                    "Live execution is disabled; no exit order was submitted"));
        }

        if (isBlank(trade.getSymbol())
                || isBlank(trade.getInstrumentKey())
                || isBlank(trade.getSide())
                || isBlank(tag)) {
            return Mono.just(exitPending(
                    request, trade.getExitOrderId(),
                    "Required trade data or exit tag is missing. "
                            + "Manual reconciliation is required."));
        }

        final String exitSide;
        if ("BUY".equalsIgnoreCase(trade.getSide())) {
            exitSide = "SELL";
        } else if ("SELL".equalsIgnoreCase(trade.getSide())) {
            exitSide = "BUY";
        } else {
            return Mono.just(exitPending(
                    request, trade.getExitOrderId(),
                    "Persisted trade side is invalid. Manual reconciliation required."));
        }

        final int quantity = requestedQuantity;

        BrokerOrderRequest brokerRequest = BrokerOrderRequest.builder()
                .symbol(trade.getSymbol())
                .instrumentKey(trade.getInstrumentKey())
                .signal(exitSide)
                .transactionType(exitSide)
                .quantity(quantity)
                .price(BigDecimal.ZERO)
                .orderType(orderType)
                .tag(tag)
                .build();

        return brokerExecutionClient.executeOrder(brokerRequest)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Empty broker exit placement response")))
                .onErrorResume(placementError -> {
                    log.error(
                            "EXIT PLACEMENT AMBIGUOUS | SignalId={} | Tag={} | Reason={}",
                            request.getSignalId(), tag,
                            safeMessage(placementError), placementError);

                    return recoverExitPlacementByTag(
                            request, tag, placementError);
                })
                .flatMap(response -> {
                    if (response == null) {
                        return Mono.just(exitPending(
                                request, null,
                                "Broker returned no usable response. "
                                        + "Manual reconciliation required."));
                    }

                    String status = response.getStatus() == null
                            ? ""
                            : response.getStatus().trim();

                    String orderId = response.getOrderId();

                    /*
                     * Release the claim only for an explicit rejection with no
                     * order ID. Unknown outcomes must remain EXIT_PENDING.
                     */
                    if ("REJECTED".equalsIgnoreCase(status)
                            && isBlank(orderId)) {
                        return Mono.fromCallable(() ->
                                        executedTradeRepository
                                                .releaseRejectedExitClaim(
                                                        request.getSignalId()))
                                .subscribeOn(Schedulers.boundedElastic())
                                .map(released -> released
                                        ? exitFailure(
                                        request,
                                        "BROKER_REJECTED",
                                        "Broker definitively rejected the exit. "
                                        + "No order ID was returned; trade "
                                        + "has been returned to OPEN. Reason: "
                                        + response.getMessage())
                                        : exitPending(
                                        request,
                                        null,
                                        "Broker rejection received, but the exit "
                                        + "claim could not safely be released. "
                                        + "Manual reconciliation is required."))
                                .onErrorResume(error -> Mono.just(exitPending(
                                        request, null,
                                        "Could not safely release rejected exit claim: "
                                                + safeMessage(error))));
                    }

                    if (isBlank(orderId)) {
                        return Mono.just(exitPending(
                                request, null,
                                "No order ID was confirmed. Placement outcome remains "
                                        + "uncertain; do not resubmit. Status=" + status
                                        + ", message=" + response.getMessage()));
                    }

                    /*
                     * An order ID exists even if the response says rejected or
                     * cancelled. Persist it and reconcile final broker fills.
                     */
                    return persistExitOrderId(trade, orderId, quantity)
                            .then(Mono.defer(() ->
                                    reconcileExitOrder(
                                            request, trade, orderId, 0)))
                            .onErrorResume(error -> {
                                log.error(
                                        "EXIT ORDER REQUIRES RECONCILIATION | "
                                                + "SignalId={} | OrderId={}",
                                        request.getSignalId(), orderId, error);

                                return Mono.just(exitPending(
                                        request, orderId,
                                        "Order ID was received but persistence or "
                                                + "reconciliation failed. Do not resubmit: "
                                                + safeMessage(error)));
                            });
                })
                .onErrorResume(error -> Mono.just(exitPending(
                        request, trade.getExitOrderId(),
                        "Exit outcome could not be confirmed. Do not resubmit; "
                                + "reconcile broker history using tag=" + tag
                                + ". Reason: " + safeMessage(error))));
    }


    private Mono<TradeExecutionResponse> reconcileExitOrder(
            TradeExitRequest request,
            ExecutedTradeRecord originalTrade,
            String orderId,
            int attempt) {

        if (isBlank(orderId)) {
            return Mono.just(exitPending(
                    request, null,
                    "Exit order ID is missing. Manual reconciliation required."));
        }

        return Mono.fromCallable(() ->
                        executedTradeRepository.findBySignalId(request.getSignalId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Trade not found during exit reconciliation")))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(currentTrade -> {
                    if (!"EXIT_PENDING".equalsIgnoreCase(currentTrade.getStatus())) {
                        return Mono.just(exitPending(
                                request, orderId,
                                "Trade is no longer EXIT_PENDING. Current status="
                                        + currentTrade.getStatus()
                                        + ". Verify broker and database state."));
                    }

                    if (!orderId.equals(currentTrade.getExitOrderId())) {
                        return Mono.just(exitPending(
                                request, orderId,
                                "Persisted exit order ID does not match the order being reconciled."));
                    }

                    Integer expectedQuantity = currentTrade.getExitOrderQuantity();

                    if (expectedQuantity == null || expectedQuantity <= 0) {
                        return Mono.just(exitPending(
                                request, orderId,
                                "Persisted exit order quantity is missing or invalid. "
                                        + "No fill was applied; manual broker reconciliation required."));
                    }

                    return brokerExecutionClient.getOrderDetails(orderId)
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "Broker returned empty exit-order details")))
                            .flatMap(details -> {
                                String status = normalizeStatus(details.getStatus());
                                Integer orderFilled = details.getFilledQuantity();
                                BigDecimal orderAverage = details.getAveragePrice();

                                if (isComplete(status) || isRejectedOrCancelled(status)) {
                                    if (orderFilled == null
                                            || orderFilled < 0
                                            || orderFilled > expectedQuantity
                                            || (orderFilled > 0
                                            && (orderAverage == null
                                            || orderAverage.compareTo(BigDecimal.ZERO) <= 0))) {
                                        return Mono.just(exitPending(
                                                request, orderId,
                                                "Broker fill details are invalid or exceed the persisted "
                                                        + "exit order quantity. Manual reconciliation required."));
                                    }

                                    return persistExitFill(
                                            request, orderId, orderFilled,
                                            orderAverage, status)
                                            .map(saved -> exitResponse(
                                                    request, saved, orderId,
                                                    status, orderFilled));
                                }

                                if (attempt >= reconciliationMaxAttempts) {
                                    return Mono.just(exitPending(
                                            request, orderId,
                                            "Exit order remains " + status
                                                    + ". Trade remains EXIT_PENDING; reconcile before retrying."));
                                }

                                return Mono.delay(Duration.ofMillis(reconciliationIntervalMs))
                                        .then(Mono.defer(() ->
                                                reconcileExitOrder(
                                                        request, originalTrade,
                                                        orderId, attempt + 1)));
                            })
                            .onErrorResume(error -> {
                                log.error(
                                        "EXIT RECONCILIATION FAILED | SignalId={} | OrderId={} | Reason={}",
                                        request.getSignalId(),
                                        orderId,
                                        safeMessage(error),
                                        error);

                                return Mono.just(exitPending(
                                        request, orderId,
                                        "Exit reconciliation failed. Trade remains pending: "
                                                + safeMessage(error)));
                            });
                })
                .onErrorResume(error -> Mono.just(exitPending(
                        request, orderId,
                        "Could not load persisted exit details: "
                                + safeMessage(error))));
    }

    private Mono<ExecutedTradeRecord> persistExitFill(
            TradeExitRequest request,
            String orderId,
            int orderFilled,
            BigDecimal orderAverage,
            String brokerStatus) {

        // Repository applies the terminal broker snapshot under a row lock.
        // Repeated reconciliation for the same order must not add its cumulative
        // filled quantity or realized P&L more than once.
        return Mono.fromCallable(() -> executedTradeRepository.applyExitFill(
                        request.getSignalId(), orderId, orderFilled, orderAverage, brokerStatus))
                .subscribeOn(Schedulers.boundedElastic());
    }


    private Mono<Void> persistExitOrderId(
            ExecutedTradeRecord trade,
            String orderId,
            Integer submittedQuantity) {

        if (trade == null || trade.getSignalId() == null) {
            return Mono.error(new IllegalArgumentException(
                    "Trade and signal ID are required to persist exit order details"));
        }

        if (orderId == null || orderId.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "Exit order ID must not be blank"));
        }

        if (submittedQuantity == null || submittedQuantity <= 0) {
            return Mono.error(new IllegalArgumentException(
                    "Submitted exit quantity must be greater than zero"));
        }

        return Mono.fromCallable(() -> {
                    boolean persisted =
                            executedTradeRepository.persistExitOrderDetailsIfPending(
                                    trade.getSignalId(),
                                    orderId,
                                    submittedQuantity);

                    if (!persisted) {
                        throw new IllegalStateException(
                                "Could not safely persist exit order details. "
                                        + "Trade may no longer be EXIT_PENDING, "
                                        + "or persisted order ID/quantity conflicts. "
                                        + "Manual reconciliation required. SignalId="
                                        + trade.getSignalId()
                                        + ", OrderId=" + orderId
                                        + ", Quantity=" + submittedQuantity);
                    }

                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }



    private String buildExitTag(
            Long signalId,
            Integer exitAttempt,
            int previousExitFilled,
            int quantity) {
        // Legacy pending rows have exit_attempt=0 and must retain the original tag
        // so startup recovery can still find orders placed before this migration.
        if (exitAttempt == null || exitAttempt <= 0) {
            return "AI-EXIT-" + signalId + "-" + previousExitFilled + "-" + quantity;
        }
        // Compact and unique per signal/attempt; also avoids broker tag-length limits.
        return "AI-X-" + signalId + "-" + exitAttempt;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private TradeExecutionResponse exitResponse(
            TradeExitRequest request,
            ExecutedTradeRecord trade,
            String orderId,
            String brokerStatus,
            int orderFilled) {
        boolean closed = "CLOSED".equalsIgnoreCase(trade.getStatus());
        return TradeExecutionResponse.builder()
                .success(closed)
                .signalId(request.getSignalId())
                .symbol(trade.getSymbol())
                .signal("BUY".equalsIgnoreCase(trade.getSide()) ? "SELL" : "BUY")
                .quantity(request.getQuantity())
                .filledQuantity(orderFilled)
                .executedPrice(trade.getExitAveragePrice())
                .orderId(orderId)
                .status(closed ? "CLOSED" : "PARTIAL_EXIT")
                .message(closed ? "Position is fully closed" :
                        "Exit order reached terminal state; remaining position quantity=" + trade.getRemainingQuantity())
                .build();
    }

    private TradeExecutionResponse exitPending(
            TradeExitRequest request, String orderId, String message) {
        return TradeExecutionResponse.builder()
                .success(false)
                .signalId(request == null ? null : request.getSignalId())
                .quantity(request == null ? null : request.getQuantity())
                .orderId(orderId)
                .status("EXIT_PENDING")
                .message(message)
                .build();
    }

    private TradeExecutionResponse exitFailure(
            TradeExitRequest request, String status, String message) {
        return TradeExecutionResponse.builder()
                .success(false)
                .signalId(request == null ? null : request.getSignalId())
                .quantity(request == null ? null : request.getQuantity())
                .status(status)
                .message(message)
                .rejectionReason(message)
                .build();
    }


    private Mono<TradeExecutionResponse> claimAndExecute(
            TradeExecutionRequest request,
            String tag,
            String orderType) {

        // Disabled mode must never create a new execution claim or place an order.
        // Existing claims can still be reconciled through broker lookups.
        if (!liveEnabled) {
            return idempotencyRepository.find(request.getSignalId())
                    .flatMap(record -> {
                        if (!matchesExecutionClaim(request, record)) {
                            return Mono.just(failure(
                                    request,
                                    "IDEMPOTENCY_CONFLICT",
                                    "The existing execution claim does not match the incoming "
                                            + "signal ID, tag, symbol, side or quantity. "
                                            + "No order was submitted; manual reconciliation is required."
                            ));
                        }
                        return recoverExistingExecution(request, record);
                    })
                    .switchIfEmpty(Mono.just(failure(
                            request,
                            "LIVE_DISABLED",
                            "Live broker execution is disabled; no new order was submitted"
                    )));
        }

        return admitAndClaim(request, tag)
                .flatMap(admission -> {
                    if (admission.rejectionReason() != null) {
                        log.warn("LIVE ENTRY REJECTED BY RISK GUARD | SignalId={} | Symbol={} | Reason={}",
                                request.getSignalId(), request.getSymbol(), admission.rejectionReason());
                        return Mono.just(failure(request, "RISK_REJECTED",
                                admission.rejectionReason() + ". No order was submitted."));
                    }
                    if (admission.claimed()) {
                        return executeBrokerOrder(request, tag, orderType);
                    }

                    // Another request already claimed this signal. Validate the
                    // original claim before attempting recovery; never submit a
                    // second broker order for the same signal.
                    return idempotencyRepository.find(request.getSignalId())
                            .flatMap(record -> {
                                if (!matchesExecutionClaim(request, record)) {
                                    return Mono.just(failure(
                                            request,
                                            "IDEMPOTENCY_CONFLICT",
                                            "The existing execution claim does not match the incoming "
                                                    + "signal ID, tag, symbol, side or quantity. "
                                                    + "No order was submitted; manual reconciliation is required."
                                    ));
                                }

                                return recoverExistingExecution(request, record);
                            })
                            .switchIfEmpty(Mono.just(failure(
                                    request,
                                    "DUPLICATE_EXECUTION",
                                    "Execution claim already exists or could not be loaded for signalId="
                                            + request.getSignalId()
                                            + "; no duplicate order was submitted"
                            )));
                });
    }

    private record EntryAdmission(boolean claimed, String rejectionReason) {
    }

    /**
     * Evaluates account limits and inserts the idempotency claim under one lock, so
     * two concurrent entries cannot both pass a position or daily limit. An existing
     * claim for the same signal is reported as not claimed (caller recovers it);
     * the limits apply only to brand-new claims.
     */
    private Mono<EntryAdmission> admitAndClaim(TradeExecutionRequest request, String tag) {
        return Mono.fromCallable(() -> {
                    entryAdmissionLock.lock();
                    try {
                        if (idempotencyRepository.find(request.getSignalId()).block() == null) {
                            String rejection = riskGuard.evaluateNewEntry(request);
                            if (rejection != null) {
                                return new EntryAdmission(false, rejection);
                            }
                        }
                        Boolean claimed = idempotencyRepository.tryClaim(
                                        request.getSignalId(),
                                        tag,
                                        request.getSymbol(),
                                        normalizeSignal(request.getSignal()),
                                        request.getQuantity(),
                                        request.getInstrumentKey(),
                                        request.getEntryPrice(),
                                        request.getRequiredMargin(),
                                        request.getStopLoss(),
                                        request.getTarget())
                                .block();
                        return new EntryAdmission(Boolean.TRUE.equals(claimed), null);
                    } finally {
                        entryAdmissionLock.unlock();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private boolean matchesExecutionClaim(
            TradeExecutionRequest request,
            ExecutionIdempotencyRecord record) {

        if (request == null || record == null) {
            return false;
        }

        String expectedTag = buildExecutionTag(request);
        String expectedSignal = normalizeSignal(request.getSignal());

        return request.getSignalId() != null
                && request.getSignalId().equals(record.getSignalId())
                && expectedTag.equals(record.getTag())
                && sameTextIgnoreCase(request.getSymbol(), record.getSymbol())
                && sameTextIgnoreCase(expectedSignal, record.getSignal())
                && request.getQuantity() != null
                && request.getQuantity().equals(record.getQuantity())
                && sameExactText(request.getInstrumentKey(), record.getInstrumentKey())
                && sameDecimal(request.getEntryPrice(), record.getEntryPrice())
                && sameDecimal(request.getRequiredMargin(), record.getRequiredMargin())
                && sameDecimal(request.getStopLoss(), record.getStopLoss())
                && sameDecimal(request.getTarget(), record.getTarget());
    }

    private boolean sameTextIgnoreCase(String expected, String actual) {
        return expected == null ? actual == null
                : actual != null && expected.equalsIgnoreCase(actual);
    }

    private boolean sameExactText(String expected, String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    private boolean sameDecimal(BigDecimal expected, BigDecimal actual) {
        return expected == null ? actual == null
                : actual != null && expected.compareTo(actual) == 0;
    }



    private Mono<TradeExecutionResponse> executeBrokerOrder(
            TradeExecutionRequest request,
            String tag,
            String orderType) {

        if (!liveEnabled) {
            return Mono.just(failure(request, "LIVE_DISABLED",
                    "Live broker execution is disabled; no new order was submitted"));
        }

        BrokerOrderRequest brokerRequest = BrokerOrderRequest.builder()
                .symbol(request.getSymbol())
                .instrumentKey(request.getInstrumentKey())
                .signal(normalizeSignal(request.getSignal()))
                .quantity(request.getQuantity())
                .price(request.getEntryPrice())
                .orderType(orderType)
                .tag(tag)
                .build();

        log.info(
                "LIVE ORDER REQUEST | SignalId={} | Symbol={} | Qty={} | Tag={}",
                request.getSignalId(),
                request.getSymbol(),
                request.getQuantity(),
                tag
        );

        Mono<TradeExecutionResponse> placement = brokerExecutionClient.executeOrder(brokerRequest)
                .switchIfEmpty(Mono.defer(() -> {
                    log.error(
                            "EMPTY BROKER PLACEMENT RESPONSE | SignalId={} | Tag={}",
                            request.getSignalId(),
                            tag
                    );

                    return Mono.error(new IllegalStateException(
                            "Broker placement returned an empty response; outcome is ambiguous"
                    ));
                }))
                // Only placement errors enter ambiguous-placement recovery.
                // Reconciliation/persistence errors must not be reclassified as placement errors.
                .map(response -> (Mono<TradeExecutionResponse>) reconcileAfterPlacement(request, response, tag))
                .onErrorResume(error -> Mono.just(recoverAfterPlacementError(request, tag, error)))
                .flatMap(result -> result);
        return placement;
    }



    private Mono<TradeExecutionResponse> recoverAfterPlacementError(
            TradeExecutionRequest request,
            String tag,
            Throwable placementError) {

        log.error(
                "BROKER PLACEMENT OUTCOME AMBIGUOUS; SEARCHING BY TAG | SignalId={} | Tag={} | Reason={}",
                request.getSignalId(),
                tag,
                safeMessage(placementError)
        );

        return brokerExecutionClient.findOrderByTag(tag)
                .flatMap(existing -> {
                    String recoveredOrderId = existing.getOrderId();

                    // An order was found, but without an ID we cannot safely reconcile it.
                    if (recoveredOrderId == null || recoveredOrderId.isBlank()) {
                        return idempotencyRepository.updateStatus(
                                        request.getSignalId(),
                                        "UNKNOWN",
                                        null,
                                        null,
                                        null,
                                        "Broker search returned an order without an ID after ambiguous placement"
                                )
                                .thenReturn(pending(
                                        request,
                                        null,
                                        "Order placement outcome is unknown. Broker order has no ID; "
                                                + "manual reconciliation is required. Do not retry."
                                ));
                    }

                    // Recover the existing idempotency claim and link the broker order.
                    // If recovery produces an empty result, return PENDING rather than
                    // allowing the chain to fall through to another execution attempt.
                    return idempotencyRepository.find(request.getSignalId())
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "Idempotency record missing while recovering ambiguous broker placement"
                            )))
                            .flatMap(record ->
                                    linkExistingBrokerOrder(request, existing, record)
                                            .thenReturn(record)
                            )
                            .then(reconcileUntilTerminal(request, recoveredOrderId, 1))
                            .switchIfEmpty(Mono.defer(() -> {
                                log.error(
                                        "BROKER RECOVERY RETURNED EMPTY | SignalId={} | OrderId={}",
                                        request.getSignalId(),
                                        recoveredOrderId
                                );

                                return idempotencyRepository.updateStatus(
                                                request.getSignalId(),
                                                "UNKNOWN",
                                                recoveredOrderId,
                                                null,
                                                null,
                                                "Broker order found, but reconciliation returned no result"
                                        )
                                        .thenReturn(pending(
                                                request,
                                                recoveredOrderId,
                                                "Existing broker order found, but its status could not be reconciled. "
                                                        + "No new order was placed."
                                        ));
                            }));
                })
                // No order was returned by the tag search. Because placement was
                // ambiguous, do not assume that no order exists and do not retry.
                .switchIfEmpty(Mono.defer(() -> {
                    log.error(
                            "NO ORDER FOUND DURING AMBIGUOUS PLACEMENT RECOVERY | SignalId={} | Tag={}",
                            request.getSignalId(),
                            tag
                    );

                    return idempotencyRepository.updateStatus(
                                    request.getSignalId(),
                                    "UNKNOWN",
                                    null,
                                    null,
                                    null,
                                    "Placement outcome ambiguous; tag search returned no order. "
                                            + safeMessage(placementError)
                            )
                            .thenReturn(pending(
                                    request,
                                    null,
                                    "Broker placement outcome remains unknown. No automatic retry was submitted; "
                                            + "manual or scheduled reconciliation is required."
                            ));
                }))
                .onErrorResume(recoveryError -> {
                    log.error(
                            "BROKER ORDER RECOVERY FAILED | SignalId={} | PlacementError={} | RecoveryError={}",
                            request.getSignalId(),
                            safeMessage(placementError),
                            safeMessage(recoveryError),
                            recoveryError
                    );

                    /*
                     * Recovery can fail after the broker order ID has already been linked.
                     * Do not overwrite that known ID with null: losing it makes later
                     * reconciliation harder and can hide a potentially live position.
                     */
                    return idempotencyRepository.find(request.getSignalId())
                            .flatMap(record -> {
                                String knownOrderId = record.getOrderId();
                                return idempotencyRepository.updateStatus(
                                                request.getSignalId(),
                                                "UNKNOWN",
                                                knownOrderId,
                                                record.getFilledQuantity(),
                                                record.getAveragePrice(),
                                                "Placement/recovery ambiguous: " + safeMessage(recoveryError)
                                        )
                                        .onErrorResume(updateError -> {
                                            log.error(
                                                    "COULD NOT PERSIST UNKNOWN EXECUTION STATE | SignalId={} | Reason={}",
                                                    request.getSignalId(),
                                                    safeMessage(updateError),
                                                    updateError
                                            );
                                            return Mono.empty();
                                        })
                                        .thenReturn(pending(
                                                request,
                                                knownOrderId,
                                                "Broker placement outcome remains unknown. "
                                                        + "Known order ID was preserved when available. "
                                                        + "Do not retry automatically; reconciliation is required."
                                        ));
                            })
                            .switchIfEmpty(Mono.defer(() ->
                                    idempotencyRepository.updateStatus(
                                                    request.getSignalId(),
                                                    "UNKNOWN",
                                                    null,
                                                    null,
                                                    null,
                                                    "Placement/recovery ambiguous: " + safeMessage(recoveryError)
                                            )
                                            .onErrorResume(updateError -> {
                                                log.error(
                                                        "COULD NOT PERSIST UNKNOWN EXECUTION STATE | SignalId={} | Reason={}",
                                                        request.getSignalId(),
                                                        safeMessage(updateError),
                                                        updateError
                                                );
                                                return Mono.empty();
                                            })
                                            .thenReturn(pending(
                                                    request,
                                                    null,
                                                    "Broker placement outcome remains unknown. "
                                                            + "No automatic retry was submitted; reconciliation is required."
                                            ))
                            ))
                            .onErrorResume(findError -> {
                                log.error(
                                        "COULD NOT LOAD EXECUTION RECORD AFTER RECOVERY FAILURE | SignalId={} | Reason={}",
                                        request.getSignalId(),
                                        safeMessage(findError),
                                        findError
                                );
                                return Mono.just(pending(
                                        request,
                                        null,
                                        "Broker placement outcome remains unknown and the execution record "
                                                + "could not be loaded. Do not retry automatically; manual reconciliation is required."
                                ));
                            });
                });
    }



    private Mono<TradeExecutionResponse> recoverExistingBrokerOrder(
            TradeExecutionRequest request,
            UpstoxOrderHistoryResponse.OrderData existing) {

        if (existing == null) {
            return Mono.just(pending(
                    request,
                    null,
                    "Broker recovery returned no order record. No new order was placed."
            ));
        }

        String orderId = existing.getOrderId();
        String expectedTag = buildExecutionTag(request);

        log.warn(
                "EXISTING BROKER ORDER RECOVERY | SignalId={} | ExpectedTag={} | BrokerTag={} | OrderId={} | Status={}",
                request.getSignalId(),
                expectedTag,
                existing.getTag(),
                orderId,
                existing.getStatus()
        );

        // An existing broker record without an order ID cannot be safely linked
        // or reconciled. Never submit a replacement order in this situation.
        if (orderId == null || orderId.isBlank()) {
            return Mono.just(pending(
                    request,
                    null,
                    "Broker order was found without an order ID. Manual reconciliation is required; "
                            + "no new order was placed."
            ));
        }

        // If the broker supplies a tag, it must match this signal.
        if (existing.getTag() != null
                && !expectedTag.equals(existing.getTag())) {
            return Mono.error(new IllegalStateException(
                    "Broker order tag does not match the signal. Manual reconciliation required. SignalId="
                            + request.getSignalId()
            ));
        }

        // First try to recover the existing idempotency record.
        // If it does not exist, attempt to establish the claim, then reload it.
        // Whether the claim succeeds or another request already owns it, the
        // record must be loaded and validated before linking the broker order.
        return idempotencyRepository.find(request.getSignalId())
                .switchIfEmpty(Mono.defer(() ->
                        idempotencyRepository.tryClaim(
                                        request.getSignalId(),
                                        expectedTag,
                                        request.getSymbol(),
                                        normalizeSignal(request.getSignal()),
                                        request.getQuantity(),
                                        request.getInstrumentKey(),
                                        request.getEntryPrice(),
                                        request.getRequiredMargin(),
                                        request.getStopLoss(),
                                        request.getTarget()
                                )
                                .then(idempotencyRepository.find(request.getSignalId()))
                                .switchIfEmpty(Mono.error(new IllegalStateException(
                                        "Could not establish or recover the idempotency record "
                                                + "for existing broker order " + orderId
                                )))
                ))
                .flatMap(record ->
                        linkExistingBrokerOrder(request, existing, record)
                                .then(Mono.defer(() ->
                                        reconcileExistingOrder(request, orderId, existing)
                                ))
                )
                // Recovery must always return a meaningful result. An empty
                // reconciliation result must not trigger fresh order execution.
                .switchIfEmpty(Mono.fromSupplier(() -> pending(
                        request,
                        orderId,
                        "Existing broker order was linked, but reconciliation returned no result. "
                                + "No new order was placed; reconciliation is required."
                )));
    }


    private Mono<Void> linkExistingBrokerOrder(
            TradeExecutionRequest request,
            UpstoxOrderHistoryResponse.OrderData brokerOrder,
            ExecutionIdempotencyRecord record) {

        if (brokerOrder == null || brokerOrder.getOrderId() == null || brokerOrder.getOrderId().isBlank()) {
            return Mono.error(new IllegalStateException("Broker order ID is required for reconciliation"));
        }
        if (brokerOrder.getTag() != null && !buildExecutionTag(request).equals(brokerOrder.getTag())) {
            return Mono.error(new IllegalStateException(
                    "Broker order tag conflicts with signal. Manual reconciliation required. SignalId="
                            + request.getSignalId()));
        }
        return linkOrderIdSafely(request, brokerOrder.getOrderId(), record);
    }

    private Mono<Void> linkOrderIdSafely(
            TradeExecutionRequest request,
            String brokerOrderId,
            ExecutionIdempotencyRecord record) {

        if (!matchesExecutionClaim(request, record)) {
            return Mono.error(new IllegalStateException(
                    "Idempotency record conflicts with request/broker order metadata. "
                            + "Manual reconciliation required. SignalId="
                            + request.getSignalId()));
        }

        if (record.getOrderId() != null && !record.getOrderId().isBlank()) {
            if (!record.getOrderId().equals(brokerOrderId)) {
                return Mono.error(new IllegalStateException(
                        "Idempotency record contains a different broker order ID. ExistingOrderId="
                                + record.getOrderId() + ", BrokerOrderId=" + brokerOrderId
                                + ", SignalId=" + request.getSignalId()));
            }
            return Mono.empty();
        }

        return idempotencyRepository.updateOrderPlaced(request.getSignalId(), brokerOrderId);
    }



    private Mono<TradeExecutionResponse> reconcileExistingOrder(
            TradeExecutionRequest request,
            String orderId,
            UpstoxOrderHistoryResponse.OrderData history) {

        if (history == null) {
            return Mono.just(pending(
                    request,
                    orderId,
                    "Broker order history is unavailable. Reconciliation is required; "
                            + "no new order was placed."
            ));
        }

        if (orderId == null || orderId.isBlank()) {
            if (isComplete(history.getStatus())) {
                return Mono.just(pending(
                        request,
                        null,
                        "Broker history reports completion but order ID is missing. "
                                + "Manual reconciliation is required."
                ));
            }

            return Mono.just(buildFromHistory(request, history));
        }

        return brokerExecutionClient.getOrderDetails(orderId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Broker returned no order details for orderId=" + orderId
                )))
                .materialize()
                .flatMap(signal -> {

                    if (signal.isOnError()) {
                        Throwable error = signal.getThrowable();

                        log.warn(
                                "ORDER DETAILS LOOKUP FAILED; USING HISTORY | SignalId={} | OrderId={} | Reason={}",
                                request.getSignalId(),
                                orderId,
                                safeMessage(error),
                                error
                        );

                        if (!isComplete(history.getStatus())) {
                            return Mono.just(buildFromHistory(request, history));
                        }

                        Integer filledQuantity = history.getFilledQuantity();
                        BigDecimal averagePrice = history.getAveragePrice();

                        if (filledQuantity == null
                                || filledQuantity <= 0
                                || filledQuantity > request.getQuantity()
                                || averagePrice == null
                                || averagePrice.compareTo(BigDecimal.ZERO) <= 0) {

                            return Mono.just(pending(
                                    request,
                                    orderId,
                                    "Completed order history contains invalid fill details. "
                                            + "Manual reconciliation is required."
                            ));
                        }

                        // Broker history is the fallback source only when the
                        // order-details lookup fails. Persistence errors below
                        // must propagate and must not be treated as lookup errors.
                        // Persist the confirmed broker exposure before marking the
                        // idempotency claim FILLED. If persistence fails or the process
                        // stops here, the claim remains unresolved and startup recovery
                        // can reconcile it instead of opening readiness with no trade row.
                        return persistObservedFill(
                                        request,
                                        orderId,
                                        filledQuantity,
                                        averagePrice
                                )
                                .then(idempotencyRepository.updateFilled(
                                        request.getSignalId(),
                                        orderId,
                                        filledQuantity,
                                        averagePrice
                                ))
                                .thenReturn(buildFromHistory(request, history));
                    }

                    UpstoxOrderDetailsResponse.DataResponse details = signal.get();

                    // Keep reconciliation and persistence outside the lookup
                    // error handler.
                    return reconcileDetails(request, orderId, details, 1);
                });
    }


    private Mono<TradeExecutionResponse> recoverExistingExecution(
            TradeExecutionRequest request,
            ExecutionIdempotencyRecord record) {

        if (record == null) {
            return Mono.just(pending(
                    request,
                    null,
                    "Execution claim could not be recovered. Manual reconciliation is required; "
                            + "no new order was placed."
            ));
        }

        log.warn(
                "DUPLICATE EXECUTION CLAIM | SignalId={} | Tag={} | Status={} | OrderId={}",
                record.getSignalId(),
                record.getTag(),
                record.getStatus(),
                record.getOrderId()
        );

        String orderId = record.getOrderId();

        if (orderId == null || orderId.isBlank()) {
            return Mono.just(TradeExecutionResponse.builder()
                    .success(false)
                    .signalId(request.getSignalId())
                    .symbol(request.getSymbol())
                    .signal(request.getSignal())
                    .quantity(request.getQuantity())
                    .requiredMargin(request.getRequiredMargin())
                    .status("IN_PROGRESS")
                    .message(
                            "Execution is already claimed, but no broker order ID is recorded. "
                                    + "Reconciliation is required; no duplicate order was placed."
                    )
                    .build());
        }

        return brokerExecutionClient.getOrderDetails(orderId)
                // An empty result is not proof that the order does not exist.
                // Convert it to an error so the recovery handler returns PENDING.
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn(
                            "EMPTY BROKER ORDER DETAILS DURING RECOVERY | SignalId={} | OrderId={}",
                            request.getSignalId(),
                            orderId
                    );

                    return Mono.error(new IllegalStateException(
                            "Broker returned empty order details for orderId=" + orderId
                    ));
                }))
                .flatMap(details ->
                        reconcileDetails(request, orderId, details, 1)
                )
                .onErrorResume(error -> {
                    log.warn(
                            "EXISTING EXECUTION RECONCILIATION FAILED | SignalId={} | OrderId={} | Reason={}",
                            request.getSignalId(),
                            orderId,
                            safeMessage(error),
                            error
                    );

                    return Mono.just(TradeExecutionResponse.builder()
                            .success(false)
                            .signalId(request.getSignalId())
                            .symbol(request.getSymbol())
                            .signal(request.getSignal())
                            .quantity(request.getQuantity())
                            .filledQuantity(record.getFilledQuantity())
                            .executedPrice(record.getAveragePrice())
                            .requiredMargin(request.getRequiredMargin())
                            .orderId(orderId)
                            .status("PENDING")
                            .rejectionReason(record.getRejectionReason())
                            .message(
                                    "Existing execution could not be reconciled. "
                                            + "The recorded order ID was preserved; "
                                            + "no new order was placed."
                            )
                            .build());
                });
    }



    private Mono<TradeExecutionResponse> reconcileAfterPlacement(
            TradeExecutionRequest request,
            BrokerOrderResponse brokerResponse,
            String tag) {

        if (brokerResponse != null && brokerResponse.isSuccess()
                && brokerResponse.getOrderId() != null
                && !brokerResponse.getOrderId().isBlank()) {

            String orderId = brokerResponse.getOrderId();
            return idempotencyRepository.find(request.getSignalId())
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "Idempotency record missing after broker accepted order")))
                    .flatMap(record -> linkOrderIdSafely(request, orderId, record).thenReturn(record))
                    .then(reconcileUntilTerminal(request, orderId, 1));
        }

        // The broker client reports REJECTED without an order ID only when the order
        // definitively does not exist (request refused, or never sent). Recording it
        // as UNKNOWN would leave an unresolved claim that blocks startup readiness.
        if (brokerResponse != null && "REJECTED".equalsIgnoreCase(brokerResponse.getStatus())
                && isBlank(brokerResponse.getOrderId())) {
            String reason = firstNonBlank(brokerResponse.getMessage(), "Broker rejected the order");
            log.warn("LIVE ENTRY REJECTED BY BROKER | SignalId={} | Tag={} | Reason={}",
                    request.getSignalId(), tag, reason);
            return idempotencyRepository.updateRejected(request.getSignalId(), null, reason)
                    .thenReturn(failureWithOrder(request, "REJECTED", null, reason));
        }

        // The broker may have accepted the order even if the HTTP response was
        // inconclusive. Search by our deterministic tag before returning failure.
        return brokerExecutionClient.findOrderByTag(tag)
                .flatMap(existing -> {
                    String recoveredOrderId = existing.getOrderId();
                    if (recoveredOrderId == null || recoveredOrderId.isBlank()) {
                        return idempotencyRepository.updateStatus(
                                        request.getSignalId(), "UNKNOWN", null, null, null,
                                        "Broker response was inconclusive and recovered order had no ID")
                                .thenReturn(pending(request, null,
                                        "Broker response was inconclusive and no order ID was available; reconcile manually"));
                    }
                    return idempotencyRepository.find(request.getSignalId())
                            .flatMap(record -> linkExistingBrokerOrder(request, existing, record).thenReturn(record))
                            .switchIfEmpty(Mono.error(new IllegalStateException(
                                    "Idempotency record missing while recovering broker response")))
                            .then(reconcileUntilTerminal(request, recoveredOrderId, 1));
                })
                .switchIfEmpty(Mono.defer(() -> idempotencyRepository.updateStatus(
                                request.getSignalId(), "UNKNOWN", null, null, null,
                                brokerResponse == null ? "No broker response" : brokerResponse.getMessage())
                        .thenReturn(failure(request, "UNKNOWN",
                                "Broker response was inconclusive; no new retry was attempted"))));
    }



    private Mono<TradeExecutionResponse> reconcileUntilTerminal(
            TradeExecutionRequest request,
            String orderId,
            int attempt) {

        return brokerExecutionClient.getOrderDetails(orderId)
                // An empty response is an uncertain broker outcome.
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Broker returned empty order details for orderId=" + orderId
                )))
                // Convert broker retrieval success/failure into a signal BEFORE
                // running reconciliation, so persistence errors aren't mistaken
                // for broker lookup errors.
                .materialize()
                .flatMap(signal -> {

                    if (signal.isOnError()) {
                        Throwable error = signal.getThrowable();

                        log.warn(
                                "BROKER ORDER LOOKUP FAILED | SignalId={} | OrderId={} | Attempt={} | Reason={}",
                                request.getSignalId(),
                                orderId,
                                attempt,
                                safeMessage(error),
                                error
                        );

                        if (attempt >= reconciliationMaxAttempts) {
                            return idempotencyRepository.updatePending(
                                            request.getSignalId(),
                                            orderId,
                                            null,
                                            null,
                                            safeMessage(error)
                                    )
                                    .thenReturn(pending(
                                            request,
                                            orderId,
                                            "Broker order status could not be confirmed after "
                                                    + attempt
                                                    + " reconciliation attempts. "
                                                    + "The order may still exist. Manual reconciliation "
                                                    + "is required; do not submit a duplicate order."
                                    ));
                        }

                        return Mono.delay(Duration.ofMillis(reconciliationIntervalMs))
                                .then(Mono.defer(() -> reconcileUntilTerminal(
                                        request,
                                        orderId,
                                        attempt + 1
                                )));
                    }

                    UpstoxOrderDetailsResponse.DataResponse details = signal.get();

                    // Intentionally outside the broker-lookup error handler.
                    // Errors from reconciliation or database persistence must
                    // propagate instead of being retried as broker lookup failures.
                    return reconcileDetails(request, orderId, details, attempt);
                });
    }




    private Mono<TradeExecutionResponse> reconcileDetails(
            TradeExecutionRequest request,
            String orderId,
            UpstoxOrderDetailsResponse.DataResponse details,
            int attempt) {

        if (details == null) {
            return Mono.error(new IllegalStateException(
                    "Broker order details are required for reconciliation"
            ));
        }

        String status = normalizeStatus(details.getStatus());

        Integer filledQuantity = details.getFilledQuantity();
        BigDecimal averagePrice = details.getAveragePrice();

        log.info(
                "ORDER RECONCILIATION | SignalId={} | OrderId={} | Attempt={} | Status={} | FilledQty={} | AvgPrice={}",
                request.getSignalId(),
                orderId,
                attempt,
                status,
                filledQuantity,
                averagePrice
        );

        if (filledQuantity != null && filledQuantity < 0) {
            return idempotencyRepository.updatePending(
                            request.getSignalId(),
                            orderId,
                            null,
                            null,
                            "Broker returned a negative cumulative filled quantity")
                    .thenReturn(pending(
                            request,
                            orderId,
                            "Broker returned an invalid negative filled quantity. "
                                    + "The order remains unresolved; manual broker reconciliation is required."));
        }

        if (isComplete(status)) {

            boolean validFill =
                    filledQuantity != null
                            && filledQuantity > 0
                            && filledQuantity <= request.getQuantity()
                            && averagePrice != null
                            && averagePrice.compareTo(BigDecimal.ZERO) > 0;

            if (!validFill) {
                return idempotencyRepository.updatePending(
                                request.getSignalId(),
                                orderId,
                                filledQuantity,
                                averagePrice,
                                "Broker reported completion with invalid fill details")
                        .thenReturn(pending(
                                request,
                                orderId,
                                "Invalid broker fill details. Manual reconciliation required."
                        ));
            }

            // COMPLETE status with fewer shares than requested is not
            // treated as a fully executed entry.
            if (filledQuantity < request.getQuantity()) {
                return persistObservedFill(
                                request,
                                orderId,
                                filledQuantity,
                                averagePrice)
                        .then(idempotencyRepository.updatePartialFilled(
                                request.getSignalId(),
                                orderId,
                                filledQuantity,
                                averagePrice,
                                "Broker reports COMPLETE but filled quantity is below requested quantity; partial exposure persisted"))
                        .then(Mono.fromSupplier(() -> TradeExecutionResponse.builder()
                                .success(false)
                                .signalId(request.getSignalId())
                                .symbol(request.getSymbol())
                                .signal(request.getSignal())
                                .quantity(request.getQuantity())
                                .filledQuantity(filledQuantity)
                                .executedPrice(averagePrice)
                                .requiredMargin(request.getRequiredMargin())
                                .orderId(orderId)
                                .status("PENDING")
                                .message(
                                        "Broker reports COMPLETE, but the filled quantity is below "
                                                + "the requested quantity. Confirmed exposure was "
                                                + "persisted; manual reconciliation is required."
                                )
                                .build()));
            }

            // Persist the confirmed broker exposure before marking the
            // idempotency claim FILLED. If persistence fails or the process
            // stops here, startup recovery must still see an unresolved claim.
            return persistObservedFill(
                            request,
                            orderId,
                            filledQuantity,
                            averagePrice)
                    .then(idempotencyRepository.updateFilled(
                            request.getSignalId(),
                            orderId,
                            filledQuantity,
                            averagePrice))
                    .thenReturn(success(request, orderId, details));
        }


        if (isRejectedOrCancelled(status)) {

            String reason = firstNonBlank(
                    details.getStatusMessage(),
                    details.getStatusMessageRaw(),
                    "Broker order was rejected or cancelled"
            );

            // A terminal order can still leave a real position if some quantity
            // was filled before the remaining quantity was rejected or cancelled.
            if (filledQuantity != null && filledQuantity > 0) {

                log.error(
                        "PARTIAL FILL ON REJECTED/CANCELLED ORDER | SignalId={} | OrderId={} | Status={} | FilledQty={} | AvgPrice={}",
                        request.getSignalId(),
                        orderId,
                        status,
                        filledQuantity,
                        averagePrice
                );

                boolean validFill =
                        filledQuantity <= request.getQuantity()
                                && averagePrice != null
                                && averagePrice.compareTo(BigDecimal.ZERO) > 0;

                if (!validFill) {
                    return idempotencyRepository.updatePending(
                                    request.getSignalId(),
                                    orderId,
                                    filledQuantity,
                                    averagePrice,
                                    "Terminal order has invalid partial-fill details")
                            .thenReturn(pending(
                                    request,
                                    orderId,
                                    "Broker reports a rejected/cancelled order with a non-zero fill, "
                                            + "but its fill details are invalid. No position was marked OPEN; "
                                            + "manual reconciliation is required."
                            ));
                }

                return persistObservedFill(
                                request,
                                orderId,
                                filledQuantity,
                                averagePrice)
                        .then(idempotencyRepository.updatePartialFilled(
                                request.getSignalId(),
                                orderId,
                                filledQuantity,
                                averagePrice,
                                "Terminal order has a partial fill; filled exposure persisted for reconciliation"))
                        .then(Mono.fromSupplier(() -> TradeExecutionResponse.builder()
                                .success(false)
                                .signalId(request.getSignalId())
                                .symbol(request.getSymbol())
                                .signal(request.getSignal())
                                .quantity(request.getQuantity())
                                .filledQuantity(filledQuantity)
                                .executedPrice(averagePrice)
                                .requiredMargin(request.getRequiredMargin())
                                .orderId(orderId)
                                .status("PENDING")
                                .rejectionReason(reason)
                                .message(
                                        "The broker order is terminal with a partial fill. "
                                                + "The filled quantity has been persisted as an OPEN position. "
                                                + "The remaining order quantity was not treated as filled; "
                                                + "verify protective-exit coverage and reconcile before retrying. "
                                                + "Broker reason: " + reason
                                )
                                .build()));
            }

            // No quantity was filled, so there is no position to persist.
            return idempotencyRepository.updateRejected(
                            request.getSignalId(),
                            orderId,
                            reason)
                    .thenReturn(failureWithOrder(
                            request,
                            status.toUpperCase(),
                            orderId,
                            reason
                    ));
        }


        if (attempt >= reconciliationMaxAttempts) {

            boolean validObservedFill =
                    filledQuantity != null
                            && filledQuantity > 0
                            && filledQuantity <= request.getQuantity()
                            && averagePrice != null
                            && averagePrice.compareTo(BigDecimal.ZERO) > 0;

            String reason = validObservedFill
                    ? "Order remains non-terminal; cancellation requested before finalizing partial exposure"
                    : "Order not terminal after reconciliation window; cancellation requested for safety";

            // Record the latest broker-observed cumulative fill in the idempotency ledger,
            // but do not mark the trade OPEN while the entry order can still fill more shares.
            Mono<?> persistPendingExposure = validObservedFill
                    ? persistPendingEntryFill(request, orderId, filledQuantity, averagePrice)
                    : Mono.empty();

            return idempotencyRepository.updatePending(
                            request.getSignalId(),
                            orderId,
                            validObservedFill ? filledQuantity : null,
                            validObservedFill ? averagePrice : null,
                            reason)
                    .then(persistPendingExposure)
                    .then(cancelAndReconcileEntry(request, orderId, 1));
        }


        return Mono.delay(Duration.ofMillis(reconciliationIntervalMs))
                .then(reconcileUntilTerminal(
                        request,
                        orderId,
                        attempt + 1
                ));
    }



    private Mono<ExecutedTradeRecord> persistPendingEntryFill(
            TradeExecutionRequest request,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice) {

        return Mono.fromCallable(() -> {
            if (request == null || request.getSignalId() == null
                    || request.getQuantity() == null || request.getQuantity() <= 0
                    || isBlank(request.getSymbol()) || isBlank(request.getSignal())
                    || isBlank(orderId) || filledQuantity == null || filledQuantity <= 0
                    || filledQuantity > request.getQuantity() || averagePrice == null
                    || averagePrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Invalid partial entry-fill details");
            }

            ExecutedTradeRecord existing = executedTradeRepository
                    .findBySignalId(request.getSignalId()).orElse(null);
            if (existing == null) {
                ExecutedTradeRecord pendingTrade = ExecutedTradeRecord.builder()
                        .signalId(request.getSignalId())
                        .symbol(request.getSymbol())
                        .instrumentKey(request.getInstrumentKey())
                        .side(normalizeSignal(request.getSignal()))
                        .requestedQuantity(request.getQuantity())
                        .filledQuantity(filledQuantity)
                        .averageEntryPrice(averagePrice)
                        .initialStopLoss(request.getStopLoss())
                        .currentStopLoss(request.getStopLoss())
                        .targetPrice(request.getTarget())
                        .status("ENTRY_PENDING")
                        .entryOrderId(orderId)
                        .exitFilledQuantity(0)
                        .remainingQuantity(filledQuantity)
                        .openedAt(LocalDateTime.now())
                        .build();
                try {
                    return executedTradeRepository.save(pendingTrade);
                } catch (DuplicateKeyException duplicate) {
                    existing = executedTradeRepository.findBySignalId(request.getSignalId())
                            .orElseThrow(() -> duplicate);
                }
            }

            if (!orderId.equals(existing.getEntryOrderId())
                    || !request.getSymbol().equalsIgnoreCase(existing.getSymbol())
                    || !normalizeSignal(request.getSignal()).equalsIgnoreCase(existing.getSide())
                    || !request.getQuantity().equals(existing.getRequestedQuantity())) {
                throw new IllegalStateException("Pending entry fill conflicts with persisted trade; manual reconciliation required");
            }

            boolean updated = executedTradeRepository.updateEntryFillAsPending(
                    request.getSignalId(), orderId, filledQuantity, averagePrice);
            if (!updated) {
                ExecutedTradeRecord current = executedTradeRepository
                        .findBySignalId(request.getSignalId()).orElseThrow();
                if (!"ENTRY_PENDING".equalsIgnoreCase(current.getStatus())
                        || !orderId.equals(current.getEntryOrderId())
                        || !filledQuantity.equals(current.getFilledQuantity())) {
                    throw new IllegalStateException(
                            "Could not safely persist partial entry exposure; manual reconciliation required");
                }
            }
            return executedTradeRepository.findBySignalId(request.getSignalId())
                    .orElseThrow(() -> new IllegalStateException("Pending entry trade disappeared"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Requests cancellation of an entry order that remained non-terminal after
     * the reconciliation window. Cancellation acknowledgement is not treated
     * as proof of a terminal order state; broker details are polled afterwards.
     * A partial fill is persisted as OPEN only after the broker reports a terminal
     * status, so later fills cannot silently appear after an exit has closed it.
     */
    private Mono<TradeExecutionResponse> cancelAndReconcileEntry(
            TradeExecutionRequest request,
            String orderId,
            int attempt) {

        return brokerExecutionClient.cancelOrder(orderId)
                .flatMap(cancelAccepted -> {
                    if (!Boolean.TRUE.equals(cancelAccepted)) {
                        return Mono.just(pending(
                                request,
                                orderId,
                                "Entry order cancellation was not confirmed. The order may still be working; "
                                        + "observed fills remain in the idempotency record and manual broker "
                                        + "reconciliation is required. No duplicate order was submitted."));
                    }
                    return verifyEntryCancellation(request, orderId, 1);
                })
                .onErrorResume(error -> {
                    log.error("ENTRY CANCELLATION UNCERTAIN | SignalId={} | OrderId={} | Reason={}",
                            request.getSignalId(), orderId, safeMessage(error), error);
                    return Mono.just(pending(
                            request,
                            orderId,
                            "Entry cancellation outcome is uncertain. The order may still be working; "
                                    + "check broker status before retrying. No duplicate order was submitted. "
                                    + "Reason: " + safeMessage(error)));
                });
    }

    private Mono<TradeExecutionResponse> verifyEntryCancellation(
            TradeExecutionRequest request,
            String orderId,
            int attempt) {

        return brokerExecutionClient.getOrderDetails(orderId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Empty broker response while verifying entry cancellation")))
                .flatMap(details -> {
                    String status = normalizeStatus(details.getStatus());
                    if (isComplete(status) || isRejectedOrCancelled(status)) {
                        return reconcileDetails(request, orderId, details, 1);
                    }
                    if (attempt >= reconciliationMaxAttempts) {
                        return Mono.just(pending(
                                request,
                                orderId,
                                "Cancellation was acknowledged but the broker order is still " + status
                                        + ". No OPEN trade was created from a non-terminal partial fill. "
                                        + "Manual reconciliation is required; do not submit a duplicate."));
                    }
                    return Mono.delay(Duration.ofMillis(reconciliationIntervalMs))
                            .then(Mono.defer(() -> verifyEntryCancellation(
                                    request, orderId, attempt + 1)));
                })
                .onErrorResume(error -> Mono.just(pending(
                        request,
                        orderId,
                        "Could not verify terminal status after cancellation. Do not retry or submit "
                                + "another order until broker history is reconciled. Reason: "
                                + safeMessage(error))));
    }

    private Mono<ExecutedTradeRecord> persistFilledTrade(
            TradeExecutionRequest request,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice) {

        return Mono.fromCallable(() -> {
            ExecutedTradeRecord existing = executedTradeRepository.findBySignalId(request.getSignalId())
                    .orElse(null);
            if (existing != null) {
                return validateExistingTrade(existing, request, orderId, filledQuantity, averagePrice);
            }

            ExecutedTradeRecord trade = ExecutedTradeRecord.builder()
                    .signalId(request.getSignalId())
                    .symbol(request.getSymbol())
                    .instrumentKey(request.getInstrumentKey())
                    .side(normalizeSignal(request.getSignal()))
                    .requestedQuantity(request.getQuantity())
                    .filledQuantity(filledQuantity)
                    .averageEntryPrice(averagePrice)
                    .initialStopLoss(request.getStopLoss())
                    .currentStopLoss(request.getStopLoss())
                    .targetPrice(request.getTarget())
                    .status("OPEN")
                    .entryOrderId(orderId)
                    .exitFilledQuantity(0)
                    .remainingQuantity(filledQuantity)
                    .exitAveragePrice(null)
                    .openedAt(LocalDateTime.now())
                    .build();

            try {
                ExecutedTradeRecord saved = executedTradeRepository.save(trade);
                log.info("LIVE TRADE PERSISTED | TradeId={} | SignalId={} | Symbol={} | Side={} | FilledQty={} | EntryPrice={} | StopLoss={} | Target={} | OrderId={}",
                        saved.getId(), saved.getSignalId(), saved.getSymbol(), saved.getSide(),
                        saved.getFilledQuantity(), saved.getAverageEntryPrice(), saved.getCurrentStopLoss(),
                        saved.getTargetPrice(), saved.getEntryOrderId());
                return saved;
            } catch (DuplicateKeyException ex) {
                // Another request may have inserted the unique signalId concurrently.
                ExecutedTradeRecord duplicate = executedTradeRepository.findBySignalId(request.getSignalId())
                        .orElseThrow(() -> ex);
                return validateExistingTrade(duplicate, request, orderId, filledQuantity, averagePrice);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ExecutedTradeRecord validateExistingTrade(
            ExecutedTradeRecord existing,
            TradeExecutionRequest request,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice) {

        boolean sameOrder = orderId != null
                && orderId.equals(existing.getEntryOrderId());

        boolean sameSymbol = request.getSymbol() != null
                && request.getSymbol().equalsIgnoreCase(existing.getSymbol());

        boolean sameSide = normalizeSignal(request.getSignal()) != null
                && normalizeSignal(request.getSignal())
                .equalsIgnoreCase(existing.getSide());

        boolean sameRequestedQuantity = request.getQuantity() != null
                && request.getQuantity().equals(existing.getRequestedQuantity());

        if (!sameOrder || !sameSymbol || !sameSide || !sameRequestedQuantity) {
            throw new IllegalStateException(
                    "Existing executed trade conflicts with broker fill. "
                            + "Manual reconciliation required. SignalId="
                            + request.getSignalId());
        }

        if ("CLOSED".equalsIgnoreCase(existing.getStatus())
                || "EXIT_PENDING".equalsIgnoreCase(existing.getStatus())) {
            throw new IllegalStateException(
                    "Cannot update an EXIT_PENDING or CLOSED trade through generic entry reconciliation. "
                            + "SignalId=" + request.getSignalId()
                            + ", Status=" + existing.getStatus());
        }

        if (filledQuantity == null
                || filledQuantity <= 0
                || filledQuantity > request.getQuantity()
                || averagePrice == null
                || averagePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Invalid broker entry-fill details. SignalId="
                            + request.getSignalId());
        }

        Integer previousQuantity = existing.getFilledQuantity();

        if (previousQuantity != null && filledQuantity < previousQuantity) {
            throw new IllegalStateException(
                    "Broker cumulative entry-fill quantity regressed. "
                            + "Manual reconciliation required. SignalId="
                            + request.getSignalId());
        }

        int exitFilled = existing.getExitFilledQuantity() == null
                ? 0
                : existing.getExitFilledQuantity();

        if (exitFilled < 0 || exitFilled > filledQuantity) {
            throw new IllegalStateException(
                    "Recorded exit quantity exceeds confirmed entry fills. "
                            + "Manual reconciliation required. SignalId="
                            + request.getSignalId());
        }

        existing.setFilledQuantity(filledQuantity);
        existing.setAverageEntryPrice(averagePrice);
        existing.setExitFilledQuantity(exitFilled);
        existing.setRemainingQuantity(filledQuantity - exitFilled);

        if (existing.getStatus() == null
                || existing.getStatus().isBlank()) {
            existing.setStatus("OPEN");
        }

        return executedTradeRepository.save(existing);
    }


    private Mono<ExecutedTradeRecord> persistObservedFill(
            TradeExecutionRequest request,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice) {

        return Mono.fromCallable(() -> {
            if (request == null
                    || request.getSignalId() == null
                    || request.getQuantity() == null
                    || request.getQuantity() <= 0
                    || isBlank(request.getSymbol())
                    || isBlank(request.getSignal())
                    || filledQuantity == null
                    || filledQuantity <= 0
                    || filledQuantity > request.getQuantity()
                    || averagePrice == null
                    || averagePrice.compareTo(BigDecimal.ZERO) <= 0
                    || isBlank(orderId)) {

                throw new IllegalArgumentException(
                        "Invalid broker fill details; manual reconciliation required");
            }

            ExecutedTradeRecord existing = executedTradeRepository
                    .findBySignalId(request.getSignalId())
                    .orElse(null);

            if (existing != null) {
                boolean sameOrder = orderId.equals(existing.getEntryOrderId());

                boolean sameSymbol = request.getSymbol()
                        .equalsIgnoreCase(existing.getSymbol());

                String normalizedSignal = normalizeSignal(request.getSignal());

                boolean sameSide = normalizedSignal != null
                        && existing.getSide() != null
                        && normalizedSignal.equalsIgnoreCase(existing.getSide());

                boolean sameRequestedQuantity =
                        request.getQuantity().equals(existing.getRequestedQuantity());

                if (!sameOrder
                        || !sameSymbol
                        || !sameSide
                        || !sameRequestedQuantity) {

                    throw new IllegalStateException(
                            "Existing trade conflicts with broker fill. "
                                    + "Manual reconciliation required. SignalId="
                                    + request.getSignalId());
                }

                String existingStatus = existing.getStatus();

                if ("CLOSED".equalsIgnoreCase(existingStatus)) {
                    throw new IllegalStateException(
                            "Cannot reopen a closed trade during entry reconciliation. "
                                    + "SignalId=" + request.getSignalId());
                }

                // Do not let entry reconciliation overwrite an active exit lifecycle.
                if ("EXIT_PENDING".equalsIgnoreCase(existingStatus)) {
                    throw new IllegalStateException(
                            "Entry reconciliation encountered an EXIT_PENDING trade. "
                                    + "Preserve exit state and reconcile both broker orders. "
                                    + "SignalId=" + request.getSignalId()
                                    + ", EntryOrderId=" + orderId
                                    + ", ExitOrderId=" + existing.getExitOrderId());
                }

                Integer previousQuantity = existing.getFilledQuantity();

                if (previousQuantity != null
                        && filledQuantity < previousQuantity) {

                    throw new IllegalStateException(
                            "Broker cumulative entry-fill quantity regressed. "
                                    + "Manual reconciliation required. SignalId="
                                    + request.getSignalId());
                }

                int exitFilled = existing.getExitFilledQuantity() == null
                        ? 0
                        : existing.getExitFilledQuantity();

                if (exitFilled < 0 || exitFilled > filledQuantity) {
                    throw new IllegalStateException(
                            "Recorded exit quantity exceeds confirmed entry fills. "
                                    + "Manual reconciliation required. SignalId="
                                    + request.getSignalId());
                }

                boolean updated = executedTradeRepository.updateEntryFillIfTradeIsActive(
                        request.getSignalId(),
                        orderId,
                        filledQuantity,
                        averagePrice);

                if (!updated) {
                    throw new IllegalStateException(
                            "Entry fill could not be updated because the trade state changed "
                                    + "or the persisted entry data no longer matches. "
                                    + "Manual reconciliation required. SignalId="
                                    + request.getSignalId());
                }

                return executedTradeRepository.findBySignalId(request.getSignalId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Trade not found after entry-fill update. SignalId="
                                        + request.getSignalId()));
            }

            ExecutedTradeRecord trade = ExecutedTradeRecord.builder()
                    .signalId(request.getSignalId())
                    .symbol(request.getSymbol())
                    .instrumentKey(request.getInstrumentKey())
                    .side(normalizeSignal(request.getSignal()))
                    .requestedQuantity(request.getQuantity())
                    .filledQuantity(filledQuantity)
                    .averageEntryPrice(averagePrice)
                    .initialStopLoss(request.getStopLoss())
                    .currentStopLoss(request.getStopLoss())
                    .targetPrice(request.getTarget())
                    .status("OPEN")
                    .entryOrderId(orderId)
                    .exitFilledQuantity(0)
                    .remainingQuantity(filledQuantity)
                    .exitAveragePrice(null)
                    .openedAt(LocalDateTime.now())
                    .build();

            try {
                return executedTradeRepository.save(trade);
            } catch (DuplicateKeyException ex) {
                throw new IllegalStateException(
                        "Concurrent trade persistence detected. "
                                + "Reload and reconcile before continuing. SignalId="
                                + request.getSignalId(),
                        ex);
            }
        }).subscribeOn(Schedulers.boundedElastic())
                // The fill is real broker exposure whether or not the stop can be placed,
                // so a protection failure must not fail entry bookkeeping (the claim would
                // stay unresolved). Runtime reconciliation retries protection.
                .flatMap(saved -> withPositionLock(saved.getSignalId(),
                        () -> ensureProtectiveStop(saved),
                        () -> Mono.just(saved))
                        .onErrorResume(error -> {
                            log.error("POSITION OPEN WITHOUT CONFIRMED BROKER STOP | SignalId={} | Symbol={} | Qty={} | Reason={}",
                                    saved.getSignalId(), saved.getSymbol(), saved.getRemainingQuantity(),
                                    safeMessage(error), error);
                            return Mono.just(saved);
                        }));
    }

    /**
     * Places one broker-side stop for the confirmed remaining position. The deterministic
     * tag is reconciled before submission; uncertain outcomes never trigger a blind retry.
     */
    private Mono<ExecutedTradeRecord> ensureProtectiveStop(ExecutedTradeRecord trade) {
        if (trade == null || trade.getSignalId() == null) {
            return Mono.error(new IllegalStateException("Cannot protect an absent persisted trade"));
        }
        // Do not submit broker orders while live execution is disabled. This also keeps
        // paper-mode reconciliation read-only.
        if (!liveEnabled) {
            return Mono.just(trade);
        }
        int remaining = trade.getRemainingQuantity() == null
                ? Math.max(0, valueOrZero(trade.getFilledQuantity()) - valueOrZero(trade.getExitFilledQuantity()))
                : trade.getRemainingQuantity();
        BigDecimal stop = trade.getCurrentStopLoss() != null
                ? trade.getCurrentStopLoss() : trade.getInitialStopLoss();
        if (remaining <= 0) {
            return Mono.just(trade);
        }
        if (stop == null || stop.compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                            trade.getSignalId(), null, "UNPROTECTED_NO_STOP_PRICE", null, null, null))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ignored -> Mono.error(new IllegalStateException(
                            "Filled position has no valid stop-loss; broker protection was not placed. SignalId="
                                    + trade.getSignalId())));
        }
        if (trade.getProtectiveOrderId() != null && !trade.getProtectiveOrderId().isBlank()) {
            if (trade.getProtectiveOrderQuantity() == null
                    || trade.getProtectiveOrderQuantity() != remaining
                    || trade.getProtectiveTriggerPrice() == null
                    || trade.getProtectiveTriggerPrice().compareTo(stop) != 0) {
                return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                trade.getSignalId(), trade.getProtectiveOrderId(),
                                "RECONCILIATION_REQUIRED", remaining, stop, trade.getProtectiveOrderTag()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(ignored -> Mono.error(new IllegalStateException(
                                "Existing protective order quantity/trigger does not match the open position. "
                                        + "No replacement submitted automatically. SignalId=" + trade.getSignalId())));
            }
            return verifyProtectiveOrder(trade, trade.getProtectiveOrderId(), remaining, stop,
                    trade.getProtectiveOrderTag());
        }

        if (trade.getProtectiveOrderStatus() != null
                && !trade.getProtectiveOrderStatus().isBlank()) {
            return Mono.error(new IllegalStateException(
                    "Protective order has a persisted unresolved state=" + trade.getProtectiveOrderStatus()
                            + "; reconcile it before any new submission. SignalId=" + trade.getSignalId()));
        }

        String tag = protectiveTag(trade.getSignalId());
        return brokerExecutionClient.findOrderByTag(tag)
                .flatMap(history -> {
                    String orderId = history.getOrderId();
                    if (isBlank(orderId)) {
                        return Mono.error(new IllegalStateException(
                                "Protective stop tag exists without an order ID; manual reconciliation required. SignalId="
                                        + trade.getSignalId()));
                    }
                    return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                    trade.getSignalId(), orderId, "RECONCILING", remaining, stop, tag))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(verifyProtectiveOrder(trade, orderId, remaining, stop, tag));
                })
                .switchIfEmpty(Mono.defer(() -> submitProtectiveStop(trade, remaining, stop, tag)));
    }

    private Mono<ExecutedTradeRecord> submitProtectiveStop(
            ExecutedTradeRecord trade, int quantity, BigDecimal stop, String tag) {
        String side = "BUY".equalsIgnoreCase(trade.getSide()) ? "SELL" : "BUY";
        String type = protectiveStopOrderType == null ? "SL-M" : protectiveStopOrderType.trim().toUpperCase();
        if (!"SL-M".equals(type) && !"SL".equals(type)) {
            return Mono.error(new IllegalStateException("execution.protection.order-type must be SL or SL-M"));
        }
        BrokerOrderRequest request = BrokerOrderRequest.builder()
                .symbol(trade.getSymbol())
                .instrumentKey(trade.getInstrumentKey())
                .signal(side)
                .transactionType(side)
                .quantity(quantity)
                .triggerPrice(stop)
                .price("SL".equals(type) ? stop : BigDecimal.ZERO)
                .orderType(type)
                .product("I")
                .validity("DAY")
                .tag(tag)
                .build();
        return brokerExecutionClient.executeOrder(request)
                .switchIfEmpty(Mono.error(new IllegalStateException("Empty protective-stop placement response")))
                .flatMap(response -> {
                    if (isBlank(response.getOrderId()) && "REJECTED".equalsIgnoreCase(response.getStatus())) {
                        // Definitive: no stop order exists at the broker.
                        return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                        trade.getSignalId(), null, "REJECTED", quantity, stop, tag))
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(Mono.error(new IllegalStateException(
                                        "Broker rejected protective stop: " + response.getMessage()
                                                + ". SignalId=" + trade.getSignalId())));
                    }
                    if (isBlank(response.getOrderId())) {
                        return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                        trade.getSignalId(), null, "SUBMISSION_OUTCOME_UNKNOWN",
                                        quantity, stop, tag))
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(Mono.error(new IllegalStateException(
                                        "Protective stop submission has no confirmed order ID. Do not retry blindly. SignalId="
                                                + trade.getSignalId())));
                    }
                    String initialStatus = response.isSuccess() ? "SUBMITTED" : "SUBMISSION_FAILED";
                    return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                    trade.getSignalId(), response.getOrderId(), initialStatus,
                                    quantity, stop, tag))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(saved -> {
                                if (!response.isSuccess()) {
                                    return Mono.error(new IllegalStateException(
                                            "Broker did not accept protective stop: " + response.getMessage()));
                                }
                                return verifyProtectiveOrder(saved, response.getOrderId(), quantity, stop, tag);
                            });
                });
    }

    private Mono<ExecutedTradeRecord> verifyProtectiveOrder(
            ExecutedTradeRecord trade, String orderId, int quantity, BigDecimal stop, String tag) {
        return brokerExecutionClient.getOrderDetails(orderId)
                .switchIfEmpty(Mono.error(new IllegalStateException("No broker details for protective order " + orderId)))
                .flatMap(details -> {
                    String status = details.getStatus() == null ? "UNKNOWN" : details.getStatus().trim();
                    boolean quantityMatches = details.getQuantity() != null && details.getQuantity() == quantity;
                    boolean triggerMatches = details.getTriggerPrice() != null
                            && details.getTriggerPrice().compareTo(stop) == 0;
                    boolean working = isWorkingProtectiveStatus(status);
                    String persistedStatus = working && quantityMatches && triggerMatches ? "ACTIVE" : "RECONCILIATION_REQUIRED";
                    return Mono.fromCallable(() -> executedTradeRepository.updateProtectiveOrderState(
                                    trade.getSignalId(), orderId, persistedStatus, quantity, stop, tag))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(saved -> {
                                if (!working || !quantityMatches || !triggerMatches) {
                                    return Mono.error(new IllegalStateException(
                                            "Broker protective stop is not confirmed active/matching. OrderId="
                                                    + orderId + ", status=" + status + ", quantity="
                                                    + details.getQuantity() + ", trigger=" + details.getTriggerPrice()));
                                }
                                log.info("BROKER STOP PROTECTION ACTIVE | SignalId={} | OrderId={} | Qty={} | Trigger={}",
                                        trade.getSignalId(), orderId, quantity, stop);
                                return Mono.just(saved);
                            });
                });
    }

    /** Upstox states in which a stop order is accepted and can still trigger. */
    private boolean isWorkingProtectiveStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase().replace('_', ' ');
        return normalized.equals("open") || normalized.equals("trigger pending")
                || normalized.equals("validation pending") || normalized.equals("put order req received")
                || normalized.equals("open pending") || normalized.equals("modified")
                || normalized.equals("not modified") || normalized.equals("not cancelled");
    }

    private boolean isTerminalStatus(String normalizedStatus) {
        return isComplete(normalizedStatus) || isRejectedOrCancelled(normalizedStatus);
    }

    private String protectiveTag(Long signalId) {
        return "AI-SL-" + signalId;
    }

    private TradeExecutionResponse success(
            TradeExecutionRequest request,
            String orderId,
            UpstoxOrderDetailsResponse.DataResponse details) {

        return TradeExecutionResponse.builder()
                .success(true)
                .signalId(request.getSignalId())
                .symbol(request.getSymbol())
                .signal(request.getSignal())
                .quantity(request.getQuantity())
                .filledQuantity(details.getFilledQuantity())
                .executedPrice(details.getAveragePrice())
                .requiredMargin(request.getRequiredMargin())
                .orderId(orderId)
                .status("FILLED")
                .message("Order filled successfully")
                .build();
    }

    private TradeExecutionResponse buildFromHistory(
            TradeExecutionRequest request,
            UpstoxOrderHistoryResponse.OrderData existing) {

        String status = normalizeStatus(existing.getStatus()).toUpperCase();
        boolean complete = isComplete(existing.getStatus());

        return TradeExecutionResponse.builder()
                .success(complete)
                .signalId(request.getSignalId())
                .symbol(request.getSymbol())
                .signal(request.getSignal())
                .quantity(request.getQuantity())
                .filledQuantity(existing.getFilledQuantity())
                .executedPrice(existing.getAveragePrice())
                .requiredMargin(request.getRequiredMargin())
                .orderId(existing.getOrderId())
                .status(status)
                .rejectionReason(existing.getStatusMessage())
                .message("Existing broker order found; duplicate order was not placed")
                .build();
    }

    private TradeExecutionResponse pending(TradeExecutionRequest request, String orderId, String message) {
        return TradeExecutionResponse.builder()
                .success(false)
                .signalId(request.getSignalId())
                .symbol(request.getSymbol())
                .signal(request.getSignal())
                .quantity(request.getQuantity())
                .requiredMargin(request.getRequiredMargin())
                .orderId(orderId)
                .status("PENDING")
                .message(message)
                .build();
    }

    private TradeExecutionResponse failureWithOrder(
            TradeExecutionRequest request,
            String status,
            String orderId,
            String reason) {
        return TradeExecutionResponse.builder()
                .success(false)
                .signalId(request.getSignalId())
                .symbol(request.getSymbol())
                .signal(request.getSignal())
                .quantity(request.getQuantity())
                .requiredMargin(request.getRequiredMargin())
                .orderId(orderId)
                .status(status)
                .rejectionReason(reason)
                .message(reason)
                .build();
    }

    private String buildExecutionTag(TradeExecutionRequest request) {
        return "AI-" + request.getSignalId();
    }

    private String normalizeOrderType(String value) {
        String type = value == null || value.isBlank() ? defaultOrderType : value;
        type = type.trim().toUpperCase();
        if (!"MARKET".equals(type) && !"LIMIT".equals(type) && !"SL".equals(type) && !"SL-M".equals(type)) {
            return "INVALID";
        }
        return type;
    }

    private String normalizeSignal(String signal) {
        return signal == null ? null : signal.trim().toUpperCase();
    }

    private String normalizeStatus(String status) {
        return status == null ? "UNKNOWN" : status.trim().toLowerCase();
    }

    private boolean isComplete(String status) {
        return "complete".equalsIgnoreCase(status);
    }

    private boolean isRejectedOrCancelled(String status) {
        return "rejected".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)
                || "canceled".equalsIgnoreCase(status)
                || "cancelled after market order".equalsIgnoreCase(status)
                || "expired".equalsIgnoreCase(status);
    }

    /** Aligns the entry request's stop and target to the exchange tick before anything is persisted. */
    private void alignProtectivePrices(TradeExecutionRequest request) {
        if (request == null) {
            return;
        }
        request.setStopLoss(PriceTicks.alignStop(request.getStopLoss(), request.getSignal(), priceTickSize));
        request.setTarget(PriceTicks.alignTarget(request.getTarget(), request.getSignal(), priceTickSize));
    }

    private TradeProtectionUpdateRequest alignProtectivePrices(
            TradeProtectionUpdateRequest request, String positionSide) {
        return new TradeProtectionUpdateRequest(
                request.signalId(),
                PriceTicks.alignStop(request.stopLoss(), positionSide, priceTickSize),
                PriceTicks.alignTarget(request.targetPrice(), positionSide, priceTickSize),
                request.targetExtended());
    }

    private String validate(TradeExecutionRequest request) {
        if (request == null) return "Request is required";
        if (request.getSignalId() == null) return "signalId is required for idempotent execution";
        if (isBlank(request.getSymbol())) return "symbol is required";
        if (isBlank(request.getInstrumentKey())) return "instrumentKey is required";
        if (!"BUY".equalsIgnoreCase(request.getSignal()) && !"SELL".equalsIgnoreCase(request.getSignal())) {
            return "signal must be BUY or SELL";
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) return "quantity must be greater than zero";
        if (request.getEntryPrice() == null || request.getEntryPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return "entryPrice must be greater than zero";
        }
        if ("INVALID".equals(normalizeOrderType(request.getOrderType()))) {
            return "orderType must be MARKET, LIMIT, SL or SL-M";
        }
        // Every live position gets a broker stop at this price; a missing or
        // wrong-side stop would leave it unprotected or trigger immediately.
        if (request.getStopLoss() == null || request.getStopLoss().compareTo(BigDecimal.ZERO) <= 0) {
            return "stopLoss must be greater than zero";
        }
        boolean buy = "BUY".equalsIgnoreCase(request.getSignal());
        if (buy ? request.getStopLoss().compareTo(request.getEntryPrice()) >= 0
                : request.getStopLoss().compareTo(request.getEntryPrice()) <= 0) {
            return buy ? "BUY stopLoss must be below entryPrice" : "SELL stopLoss must be above entryPrice";
        }
        // requiredMargin is sizing metadata owned by Signal Service. It is not used
        // to calculate or reserve position size in TradeExecutionService.
        return null;
    }


    private Mono<BrokerOrderResponse> recoverExitPlacementByTag(
            TradeExitRequest request,
            String tag,
            Throwable placementError) {

        log.warn(
                "SEARCHING BROKER HISTORY AFTER UNCERTAIN EXIT PLACEMENT | SignalId={} | Tag={} | Reason={}",
                request.getSignalId(),
                tag,
                safeMessage(placementError));

        return brokerExecutionClient.findOrderByTag(tag)
                .flatMap(existing -> {
                    if (existing == null || isBlank(existing.getOrderId())) {
                        log.error(
                                "EXIT ORDER FOUND WITHOUT USABLE ID | SignalId={} | Tag={}",
                                request.getSignalId(),
                                tag);

                        return Mono.just(BrokerOrderResponse.builder()
                                .success(false)
                                .status("UNKNOWN")
                                .message(
                                        "Broker history returned no usable order ID. "
                                                + "Placement remains uncertain; manual reconciliation required.")
                                .build());
                    }

                    log.warn(
                            "EXISTING EXIT ORDER RECOVERED | SignalId={} | OrderId={} | Tag={} | Status={}",
                            request.getSignalId(),
                            existing.getOrderId(),
                            tag,
                            existing.getStatus());

                    return Mono.just(BrokerOrderResponse.builder()
                            .success(true)
                            .orderId(existing.getOrderId())
                            .status(existing.getStatus())
                            .message("Existing exit order recovered from broker history")
                            .build());
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.error(
                            "EXIT ORDER NOT FOUND IN HISTORY AFTER AMBIGUOUS PLACEMENT | SignalId={} | Tag={}",
                            request.getSignalId(),
                            tag);

                    return Mono.just(BrokerOrderResponse.builder()
                            .success(false)
                            .status("UNKNOWN")
                            .message(
                                    "No matching order was returned by broker history. "
                                            + "This does not prove placement failed; do not resubmit automatically.")
                            .build());
                }))
                .onErrorResume(lookupError -> {
                    log.error(
                            "EXIT ORDER TAG LOOKUP FAILED | SignalId={} | Tag={} | Reason={}",
                            request.getSignalId(),
                            tag,
                            safeMessage(lookupError),
                            lookupError);

                    return Mono.just(BrokerOrderResponse.builder()
                            .success(false)
                            .status("UNKNOWN")
                            .message(
                                    "Could not confirm exit placement through broker history. "
                                            + "Manual reconciliation is required; do not resubmit automatically.")
                            .build());
                });
    }


    private TradeExecutionResponse failure(TradeExecutionRequest request, String status, String message) {
        return TradeExecutionResponse.builder()
                .success(false)
                .signalId(request == null ? null : request.getSignalId())
                .symbol(request == null ? null : request.getSymbol())
                .signal(request == null ? null : request.getSignal())
                .quantity(request == null ? null : request.getQuantity())
                .requiredMargin(request == null ? null : request.getRequiredMargin())
                .status(status)
                .message(message)
                .build();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private boolean isProtectiveOrderTerminalWithoutFill(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        // FILL_APPLIED: a partial stop fill was booked and the stop order is terminal.
        // NOT_FOUND: a confirmed broker lookup found no stop order for the tag.
        return "CANCELLED".equals(normalized)
                || "CANCELED".equals(normalized)
                || "REJECTED".equals(normalized)
                || "EXPIRED".equals(normalized)
                || "FILL_APPLIED".equals(normalized)
                || "NOT_FOUND".equals(normalized);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "Unknown error" : error.getMessage();
    }
}
