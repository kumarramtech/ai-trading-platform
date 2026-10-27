package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.client.TradeExecutionClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.market.ExitReason;
import com.ram.trading.signal.engine.dto.market.OpenPosition;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.exit.ExitOrchestrator;
import com.ram.trading.signal.engine.exit.ExitType;
import com.ram.trading.signal.engine.exit.TradeExitService;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.service.PaperTradingService;
import com.ram.trading.signal.engine.service.TrailingStopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExitServiceImpl implements TradeExitService {

    private final PaperTradeRepository paperTradeRepository;

    private final TradeExecutionClient tradeExecutionClient;

    private final ExitOrchestrator exitOrchestrator;

    private final PaperTradingService paperTradingService;

    private final TrailingStopService trailingStopService;

    /*
     * ============================================================
     * HARD MAX LOSS
     * ============================================================
     *
     * This is a SECONDARY protection layer.
     *
     * Entry sizing already limits the planned risk per trade.
     *
     * This guard protects against:
     *
     * - Price jumps
     * - Tick gaps
     * - Delayed ticks
     * - Stop-loss slippage
     * - Any situation where actual observed P/L
     *   becomes worse than the planned risk
     *
     * Default = ₹400
     * ============================================================
     */
    @Value("${trading.risk.max-loss-per-trade:250}")
    private double maxLossPerTrade;

    @Value("${trading.exit.dynamic-target.enabled:true}")
    private boolean dynamicTargetEnabled;

    @Value("${trading.exit.dynamic-target.min-minutes-remaining:120}")
    private long minMinutesRemainingForTargetExtension;

    @Value("${trading.exit.dynamic-target.extension-percent:25}")
    private double targetExtensionPercent;

    /**
     * Must stay aligned with SignalGenerationServiceImpl. PAPER remains the
     * default and preserves the existing PaperTradingService exit path.
     */
    @Value("${execution.trade:paper}")
    private String executionTradeMode;

    /** Independent safety gate. Keep false until live lifecycle testing passes. */
    @Value("${execution.live-enabled:false}")
    private boolean liveExecutionEnabled;

    /**
     * How long one read of Trade Execution's open positions serves the ticks of all
     * symbols. Without it every tick of every symbol made an HTTP call and a DB query.
     */
    @Value("${trading.live.open-positions-cache-ms:1000}")
    private long openPositionsCacheMs = 1000L;

    /** Exchange tick used to align live trailing stops (must match Trade Execution). */
    @Value("${trading.price.tick-size:0.05}")
    private double priceTickSize = 0.05d;

    /** Positions are squared off from this time, so target extension counts down to it. */
    @Value("${trading.market.close:15:00}")
    private LocalTime squareOffTime = LocalTime.of(15, 0);

    private record CachedOpenTrades(
            Mono<List<TradeExecutionClient.TradeExecutionPosition>> trades,
            long expiresAtNanos) {
    }

    private final AtomicReference<CachedOpenTrades> openTradesCache = new AtomicReference<>();

    /**
     * Signal IDs with a live exit or protection update in flight. Ticks arriving
     * meanwhile skip that position instead of firing duplicate requests that Trade
     * Execution would reject as busy.
     */
    private final Set<Long> liveOperationsInFlight = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<Void> evaluateExit(Tick tick) {
        if (tick == null || tick.getSymbol() == null
                || tick.getLastTradedPrice() == null
                || !Double.isFinite(tick.getLastTradedPrice())
                || tick.getLastTradedPrice() <= 0) {
            log.debug("Skipping exit evaluation because tick data is invalid");
            return Mono.empty();
        }

        String mode = executionTradeMode == null
                ? "paper" : executionTradeMode.trim().toLowerCase(java.util.Locale.ROOT);

        if ("paper".equals(mode)) {
            return evaluatePaperExit(tick);
        }

        if (!"live".equals(mode)) {
            return Mono.error(new IllegalStateException(
                    "Invalid execution.trade value for exit evaluation: " + executionTradeMode));
        }

        if (!liveExecutionEnabled) {
            log.debug("Live exit evaluation blocked: execution.live-enabled=false | symbol={}",
                    tick.getSymbol());
            return Mono.empty();
        }

        return evaluateLiveExit(tick);
    }

    /** Existing paper exit implementation is intentionally kept unchanged. */
    private Mono<Void> evaluatePaperExit(Tick tick) {

        log.debug("==========================================");
        log.debug("TRADE EXIT EVALUATION STARTED");
        log.debug("Symbol        : {}", tick.getSymbol());
        log.debug("Current Price : {}", tick.getLastTradedPrice());
        log.debug("==========================================");


        /*
         * ============================================================
         * STEP 1 : FIND OPEN PAPER TRADE
         * ============================================================
         */

        PaperTrade trade =
                paperTradeRepository
                        .findTopBySymbolAndStatusOrderByEntryTimeDesc(
                                tick.getSymbol(),
                                SignalStatus.OPEN)
                        .orElse(null);


        if (trade == null) {

            log.debug(
                    "No OPEN Trade found for {}",
                    tick.getSymbol());

            return Mono.empty();
        }


        /*
         * ============================================================
         * STEP 2 : LOG CURRENT TRADE STATE
         * ============================================================
         */

        log.debug("OPEN Trade Found");

        paperTradingService.recordPrice(trade, tick.getLastTradedPrice());

        log.debug(
                "TradeId      : {}",
                trade.getId());

        log.debug(
                "Entry Price  : {}",
                trade.getEntryPrice());

        log.debug(
                "Target Price : {}",
                trade.getTargetPrice());

        log.debug(
                "Original Target : {}",
                trade.getOriginalTargetPrice());

        log.debug(
                "Stop Loss    : {}",
                trade.getStopLoss());

        log.debug(
                "Current SL   : {}",
                trade.getCurrentStopLoss());

        log.debug(
                "Quantity     : {}",
                trade.getQuantity());

        log.debug(
                "Target Extended : {}",
                trade.getTargetExtended());


        /*
         * ============================================================
         * STEP 3 : UPDATE TRAILING STOP
         * ============================================================
         */

        return trailingStopService
                .updateTrailingStop(
                        trade,
                        tick)

                .flatMap(updatedTrade -> {


                    /*
                     * ====================================================
                     * STEP 4 : BUILD OPEN POSITION
                     * ====================================================
                     */

                    OpenPosition position =
                            map(updatedTrade);


                    log.debug(
                            "EXIT EVALUATION STATE | " +
                                    "Symbol={} | " +
                                    "Price={} | " +
                                    "Entry={} | " +
                                    "Target={} | " +
                                    "OriginalTarget={} | " +
                                    "InitialStop={} | " +
                                    "CurrentStop={} | " +
                                    "TrailingStep={} | " +
                                    "TargetExtended={}",

                            updatedTrade.getSymbol(),

                            tick.getLastTradedPrice(),

                            updatedTrade.getEntryPrice(),

                            updatedTrade.getTargetPrice(),

                            updatedTrade.getOriginalTargetPrice(),

                            updatedTrade.getInitialStopLoss(),

                            updatedTrade.getCurrentStopLoss(),

                            updatedTrade.getTrailingStep(),

                            updatedTrade.getTargetExtended());


                    /*
                     * ====================================================
                     * STEP 5 : HARD MAX LOSS GUARD
                     * ====================================================
                     */

                    double entryPrice =
                            updatedTrade.getEntryPrice();

                    double currentPrice =
                            tick.getLastTradedPrice();

                    int quantity =
                            updatedTrade.getQuantity();


                    double currentProfitLoss;


                    if (SignalType.SELL.name()
                            .equalsIgnoreCase(
                                    updatedTrade.getSignal())) {

                        currentProfitLoss =
                                (entryPrice - currentPrice)
                                        * quantity;

                    } else {

                        currentProfitLoss =
                                (currentPrice - entryPrice)
                                        * quantity;
                    }


                    log.debug(
                            "CURRENT TRADE P/L | " +
                                    "Symbol={} | " +
                                    "Signal={} | " +
                                    "Entry={} | " +
                                    "Current={} | " +
                                    "Qty={} | " +
                                    "PnL={} | " +
                                    "MaxLoss={}",

                            updatedTrade.getSymbol(),

                            updatedTrade.getSignal(),

                            entryPrice,

                            currentPrice,

                            quantity,

                            currentProfitLoss,

                            maxLossPerTrade);


                    /*
                     * ====================================================
                     * HARD MAX LOSS HIT
                     * ====================================================
                     */

                    if (currentProfitLoss <= -maxLossPerTrade) {

                        log.warn(
                                "==================================================");

                        log.warn(
                                "HARD MAX LOSS TRIGGERED");

                        log.warn(
                                "TradeId      : {}",
                                updatedTrade.getId());

                        log.warn(
                                "Symbol       : {}",
                                updatedTrade.getSymbol());

                        log.warn(
                                "Entry Price  : {}",
                                entryPrice);

                        log.warn(
                                "Current Price: {}",
                                currentPrice);

                        log.warn(
                                "Current P/L  : {}",
                                currentProfitLoss);

                        log.warn(
                                "Maximum Loss : {}",
                                maxLossPerTrade);

                        log.warn(
                                "Action       : FORCE EXIT");

                        log.warn(
                                "==================================================");


                        ExitDecision hardLossDecision =
                                ExitDecision.builder()
                                        .exit(true)
                                        .reason(
                                                ExitReason.HARD_LOSS)
                                        .exitPrice(currentPrice)
                                        .exitType(
                                                ExitType.STOP_LOSS)
                                        .build();


                        return paperTradingService.closeTrade(
                                updatedTrade,
                                hardLossDecision,
                                tick);
                    }


                    /*
                     * ====================================================
                     * STEP 6 : DYNAMIC TARGET EXTENSION
                     * ====================================================
                     */

                    if (shouldExtendTarget(
                            updatedTrade,
                            tick)) {

                        log.info(
                                "TARGET EXTENSION ELIGIBLE | " +
                                        "Symbol={} | " +
                                        "Signal={} | " +
                                        "Entry={} | " +
                                        "CurrentPrice={} | " +
                                        "CurrentTarget={}",

                                updatedTrade.getSymbol(),

                                updatedTrade.getSignal(),

                                updatedTrade.getEntryPrice(),

                                currentPrice,

                                updatedTrade.getTargetPrice());


                        return extendTarget(
                                updatedTrade,
                                tick)
                                .then();
                    }


                    /*
                     * ====================================================
                     * STEP 7 : NORMAL EXIT EVALUATION
                     * ====================================================
                     */

                    ExitDecision decision =
                            exitOrchestrator.evaluate(
                                    position,
                                    tick);


                    log.debug(
                            "Exit Decision : {}",
                            decision);


                    /*
                     * ====================================================
                     * NO EXIT
                     * ====================================================
                     */

                    if (!decision.isExit()) {

                        log.debug(
                                "Trade should continue.");

                        return Mono.empty();
                    }


                    /*
                     * ====================================================
                     * STEP 8 : FINAL EXIT SNAPSHOT
                     * ====================================================
                     */

                    double exitPrice =
                            tick.getLastTradedPrice();


                    double profitLoss;


                    if (SignalType.SELL.name()
                            .equalsIgnoreCase(
                                    updatedTrade.getSignal())) {

                        profitLoss =
                                (entryPrice - exitPrice)
                                        * updatedTrade.getQuantity();

                    } else {

                        profitLoss =
                                (exitPrice - entryPrice)
                                        * updatedTrade.getQuantity();
                    }


                    /*
                     * ====================================================
                     * STEP 9 : HOLDING TIME
                     * ====================================================
                     */

                    long holdingSeconds = 0;


                    if (updatedTrade.getEntryTime() != null) {

                        holdingSeconds =
                                java.time.Duration.between(
                                                updatedTrade.getEntryTime(),
                                                LocalDateTime.now())
                                        .getSeconds();
                    }


                    /*
                     * ====================================================
                     * STEP 10 : EXIT SNAPSHOT
                     * ====================================================
                     */

                    log.info(
                            "TRADE_EXIT_SNAPSHOT | " +
                                    "TradeId={} | " +
                                    "Symbol={} | " +
                                    "Signal={} | " +
                                    "Entry={} | " +
                                    "Exit={} | " +
                                    "OriginalTarget={} | " +
                                    "FinalTarget={} | " +
                                    "TargetExtended={} | " +
                                    "TargetExtensionReason={} | " +
                                    "TargetExtendedTime={} | " +
                                    "InitialStop={} | " +
                                    "CurrentStop={} | " +
                                    "Qty={} | " +
                                    "Reason={} | " +
                                    "PnL={} | " +
                                    "HoldingSeconds={} | " +
                                    "ExitTime={}",

                            updatedTrade.getId(),

                            updatedTrade.getSymbol(),

                            updatedTrade.getSignal(),

                            entryPrice,

                            exitPrice,

                            updatedTrade.getOriginalTargetPrice(),

                            updatedTrade.getTargetPrice(),

                            updatedTrade.getTargetExtended(),

                            updatedTrade.getTargetExtensionReason(),

                            updatedTrade.getTargetExtendedTime(),

                            updatedTrade.getInitialStopLoss(),

                            updatedTrade.getCurrentStopLoss(),

                            updatedTrade.getQuantity(),

                            decision.getReason(),

                            profitLoss,

                            holdingSeconds,

                            LocalDateTime.now());


                    /*
                     * ====================================================
                     * STEP 11 : CLOSE TRADE
                     * ====================================================
                     */

                    return paperTradingService.closeTrade(
                            updatedTrade,
                            decision,
                            tick);
                });
    }

    /**
     * Evaluates only positions owned by Trade Execution Service. It does not
     * read or mutate PaperTrade rows. A submitted exit is never retried here;
     * Trade Execution Service owns idempotency and broker reconciliation.
     */
    private Mono<Void> evaluateLiveExit(Tick tick) {
        return openTrades()
                .flatMapMany(Flux::fromIterable)
                .filter(position -> position != null
                        && position.signalId() != null
                        && position.symbol() != null
                        && position.symbol().equalsIgnoreCase(tick.getSymbol())
                        && "OPEN".equalsIgnoreCase(position.status()))
                .doOnNext(position -> launchLivePositionEvaluation(position, tick))
                .then()
                .onErrorResume(error -> {
                    log.error("Live position lookup/exit evaluation failed; no blind retry. "
                                    + "Symbol={} | Error={}",
                            tick.getSymbol(), error.toString());
                    return Mono.empty();
                });
    }

    /**
     * Runs the evaluation of one position outside the tick request. An exit
     * (cancel broker stop, place, confirm) can take several seconds; inside the
     * request it blocked that market-stream lane, delayed every later tick for the
     * lane's symbols and was cut off by the stream's 5s timeout.
     */
    private void launchLivePositionEvaluation(
            TradeExecutionClient.TradeExecutionPosition position,
            Tick tick) {
        Long signalId = position.signalId();
        if (!liveOperationsInFlight.add(signalId)) {
            log.debug("LIVE POSITION EVALUATION SKIPPED | signalId={} | symbol={} | Previous operation still in flight",
                    signalId, position.symbol());
            return;
        }
        Mono.defer(() -> evaluateLivePosition(position, tick))
                .doFinally(signal -> liveOperationsInFlight.remove(signalId))
                .subscribe(
                        ignored -> { },
                        error -> log.error("LIVE POSITION EVALUATION FAILED; no blind retry | signalId={} | symbol={} | Error={}",
                                signalId, position.symbol(), error.toString()));
    }

    /** Open live positions, shared by all ticks for a short window (one in-flight read). */
    private Mono<List<TradeExecutionClient.TradeExecutionPosition>> openTrades() {
        long now = System.nanoTime();
        CachedOpenTrades cached = openTradesCache.get();
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return cached.trades();
        }
        Mono<List<TradeExecutionClient.TradeExecutionPosition>> fresh =
                tradeExecutionClient.getOpenTrades().cache();
        CachedOpenTrades next = new CachedOpenTrades(
                fresh, now + Math.max(0L, openPositionsCacheMs) * 1_000_000L);
        if (openTradesCache.compareAndSet(cached, next)) {
            return fresh;
        }
        CachedOpenTrades winner = openTradesCache.get();
        return winner != null ? winner.trades() : fresh;
    }

    /** Positions changed (exit or protection update): the next tick must read fresh state. */
    private void invalidateOpenTrades() {
        openTradesCache.set(null);
    }

    private Mono<Void> evaluateLivePosition(
            TradeExecutionClient.TradeExecutionPosition position,
            Tick tick) {

        Integer remaining = position.remainingQuantity();
        Integer filled = position.filledQuantity();
        if (remaining == null || remaining <= 0 || filled == null || filled <= 0
                || position.averageEntryPrice() == null
                || position.averageEntryPrice().signum() <= 0) {
            log.error("Skipping invalid live position; manual reconciliation may be required. "
                            + "signalId={} | symbol={} | filled={} | remaining={} | entryPrice={}",
                    position.signalId(), position.symbol(), filled, remaining,
                    position.averageEntryPrice());
            return Mono.empty();
        }

        String side = position.side() == null ? "" : position.side().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            log.error("Skipping live position with invalid side. signalId={} | side={}",
                    position.signalId(), position.side());
            return Mono.empty();
        }

        double entry = position.averageEntryPrice().doubleValue();
        double price = tick.getLastTradedPrice();

        // The hard-loss guard must consider the whole position lifecycle.
        // After a partial exit, realized P&L is already locked in; checking only
        // the remaining shares can understate the trade's cumulative loss.
        double unrealizedPnl = ("SELL".equals(side) ? entry - price : price - entry) * remaining;
        double realizedPnl = position.realizedPnl() == null
                ? 0.0d : position.realizedPnl().doubleValue();
        double totalTradePnl = realizedPnl + unrealizedPnl;

        double stop = position.currentStopLoss() != null
                ? position.currentStopLoss().doubleValue()
                : position.initialStopLoss() == null ? Double.NaN
                : position.initialStopLoss().doubleValue();
        Double target = position.targetPrice() == null
                ? null : position.targetPrice().doubleValue();

        String exitReason = null;
        if (totalTradePnl <= -Math.abs(maxLossPerTrade)) {
            exitReason = "HARD_MAX_LOSS";
        } else if (Double.isFinite(stop)
                && (("BUY".equals(side) && price <= stop)
                || ("SELL".equals(side) && price >= stop))) {
            exitReason = "STOP_LOSS";
        } else if (target != null
                && (("BUY".equals(side) && price >= target)
                || ("SELL".equals(side) && price <= target))) {
            if (shouldExtendLiveTarget(position, tick)) {
                return extendLiveTarget(position, tick)
                        .flatMap(extended -> extended
                                ? Mono.empty()
                                : submitLiveExit(position, tick, "TARGET", remaining,
                                        entry, price, realizedPnl, unrealizedPnl, totalTradePnl));
            }
            exitReason = "TARGET";
        }

        if (exitReason == null) {
            return updateLiveTrailingStop(position, tick, stop)
                    .doOnSuccess(updated -> log.debug(
                            "LIVE POSITION HOLD | signalId={} | symbol={} | price={} | realizedPnl={} | "
                                    + "unrealizedPnl={} | totalTradePnl={} | stop={} | target={} | trailingUpdateConfirmed={}",
                            position.signalId(), position.symbol(), price, realizedPnl,
                            unrealizedPnl, totalTradePnl, stop, target, Boolean.TRUE.equals(updated)))
                    .then();
        }

        return submitLiveExit(position, tick, exitReason, remaining,
                entry, price, realizedPnl, unrealizedPnl, totalTradePnl);
    }

    /**
     * Calculate a live trailing stop in Signal Engine and rely on it only when
     * Trade Execution confirms the persisted OPEN position contains that exact
     * stop. This updates internal trigger data only; no broker stop is placed.
     */
    private Mono<Boolean> updateLiveTrailingStop(
            TradeExecutionClient.TradeExecutionPosition position,
            Tick tick,
            double currentStop) {

        if (position.initialStopLoss() == null || !Double.isFinite(currentStop)
                || position.averageEntryPrice() == null) {
            return Mono.just(false);
        }

        java.util.Optional<BigDecimal> candidate = trailingStopService.calculateLiveTrailingStop(
                position.side(),
                position.averageEntryPrice(),
                position.initialStopLoss(),
                BigDecimal.valueOf(currentStop),
                tick.getLastTradedPrice(),
                position.remainingQuantity() != null ? position.remainingQuantity() : 0);
        if (candidate.isEmpty()) {
            return Mono.just(false);
        }

        // Trade Execution aligns stops to the exchange tick before modifying the broker
        // order; propose the aligned value so the confirmation below can match it.
        BigDecimal proposedStop = com.ram.trading.signal.engine.util.PriceTicks.alignStop(
                candidate.get(), position.side(), BigDecimal.valueOf(priceTickSize));
        TradeExecutionClient.TradeProtectionUpdateRequest request =
                new TradeExecutionClient.TradeProtectionUpdateRequest(
                        position.signalId(), proposedStop, null, null);

        return tradeExecutionClient.updateProtection(request)
                .doFinally(signal -> invalidateOpenTrades())
                .map(updated -> {
                    boolean confirmed = updated != null
                            && java.util.Objects.equals(position.signalId(), updated.signalId())
                            && "OPEN".equalsIgnoreCase(updated.status())
                            && updated.currentStopLoss() != null
                            && updated.currentStopLoss().compareTo(proposedStop) == 0;
                    if (confirmed) {
                        log.info("LIVE TRAILING STOP CONFIRMED | signalId={} | symbol={} | side={} | oldStop={} | newStop={} | price={}",
                                position.signalId(), position.symbol(), position.side(),
                                currentStop, proposedStop, tick.getLastTradedPrice());
                    } else {
                        log.error("LIVE TRAILING STOP NOT CONFIRMED | signalId={} | symbol={} | proposedStop={} | responseStatus={} | responseStop={}",
                                position.signalId(), position.symbol(), proposedStop,
                                updated == null ? null : updated.status(),
                                updated == null ? null : updated.currentStopLoss());
                    }
                    return confirmed;
                })
                .defaultIfEmpty(false)
                .onErrorResume(error -> {
                    log.error("LIVE TRAILING STOP UPDATE FAILED; previous persisted stop remains authoritative | signalId={} | symbol={} | proposedStop={} | error={}",
                            position.signalId(), position.symbol(), proposedStop, error.toString());
                    return Mono.just(false);
                });
    }

    private boolean shouldExtendLiveTarget(
            TradeExecutionClient.TradeExecutionPosition position,
            Tick tick) {
        if (!dynamicTargetEnabled || position == null || tick == null
                || Boolean.TRUE.equals(position.targetExtended())
                || position.targetPrice() == null
                || position.averageEntryPrice() == null
                || position.averageEntryPrice().signum() <= 0
                || !Double.isFinite(targetExtensionPercent)
                || targetExtensionPercent <= 0) {
            return false;
        }

        LocalTime marketClose = squareOffTime;
        LocalTime currentTime = LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        long minutesRemaining = java.time.Duration.between(currentTime, marketClose).toMinutes();
        if (minutesRemaining < minMinutesRemainingForTargetExtension) {
            return false;
        }

        String side = position.side() == null ? ""
                : position.side().trim().toUpperCase(java.util.Locale.ROOT);
        double price = tick.getLastTradedPrice();
        double target = position.targetPrice().doubleValue();
        return ("BUY".equals(side) && price >= target)
                || ("SELL".equals(side) && price <= target);
    }

    /**
     * Extends a live target at most once, using the same configured percentage and
     * time window as the existing paper-trading feature. The update is persisted
     * by Trade Execution before subsequent ticks evaluate the new target.
     */
    private Mono<Boolean> extendLiveTarget(
            TradeExecutionClient.TradeExecutionPosition position,
            Tick tick) {
        double oldTarget = position.targetPrice().doubleValue();
        double entry = position.averageEntryPrice().doubleValue();
        double extension = Math.abs(oldTarget - entry) * (targetExtensionPercent / 100.0d);
        double rawTarget = "BUY".equalsIgnoreCase(position.side())
                ? oldTarget + extension : oldTarget - extension;
        BigDecimal newTarget = com.ram.trading.signal.engine.util.PriceTicks.alignTarget(
                BigDecimal.valueOf(rawTarget), position.side(), BigDecimal.valueOf(priceTickSize));
        if (newTarget.signum() <= 0) {
            log.warn("LIVE TARGET EXTENSION REJECTED: computed target is invalid | signalId={} | target={}",
                    position.signalId(), newTarget);
            return Mono.just(false);
        }

        TradeExecutionClient.TradeProtectionUpdateRequest request =
                new TradeExecutionClient.TradeProtectionUpdateRequest(
                        position.signalId(), null, newTarget, true);
        return tradeExecutionClient.updateProtection(request)
                .doFinally(signal -> invalidateOpenTrades())
                .map(updated -> updated != null
                        && "OPEN".equalsIgnoreCase(updated.status())
                        && Boolean.TRUE.equals(updated.targetExtended())
                        && updated.targetPrice() != null
                        && updated.targetPrice().compareTo(newTarget) == 0)
                .defaultIfEmpty(false)
                .onErrorResume(error -> {
                    log.error("LIVE TARGET EXTENSION COULD NOT BE CONFIRMED | signalId={} | target={} | error={}",
                            position.signalId(), newTarget, error.toString());
                    return Mono.just(false);
                });
    }

    private Mono<Void> submitLiveExit(
            TradeExecutionClient.TradeExecutionPosition position,
            Tick tick,
            String exitReason,
            int remaining,
            double entry,
            double price,
            double realizedPnl,
            double unrealizedPnl,
            double totalTradePnl) {
        String side = position.side() == null ? "UNKNOWN" : position.side();
        log.warn("LIVE EXIT TRIGGERED | signalId={} | symbol={} | side={} | reason={} | "
                        + "entry={} | price={} | remainingQty={} | realizedPnl={} | "
                        + "unrealizedPnl={} | totalTradePnl={}",
                position.signalId(), position.symbol(), side, exitReason,
                entry, price, remaining, realizedPnl, unrealizedPnl, totalTradePnl);

        TradeExecutionClient.TradeExitRequest request =
                new TradeExecutionClient.TradeExitRequest(position.signalId(), remaining, "MARKET");
        // Deliberately no retry operator: a timeout may follow broker acceptance.
        return tradeExecutionClient.exitTrade(request)
                .doOnNext(response -> {
                    String status = response.status() == null
                            ? "UNKNOWN" : response.status().toUpperCase(java.util.Locale.ROOT);
                    if ("CLOSED".equals(status)) {
                        log.info("LIVE EXIT CONFIRMED CLOSED | signalId={} | symbol={} | orderId={}",
                                position.signalId(), position.symbol(), response.orderId());
                    } else if ("PARTIAL_EXIT".equals(status)) {
                        log.warn("LIVE EXIT PARTIAL | signalId={} | symbol={} | orderId={} | "
                                        + "filledQty={} | follow-up must use refreshed persisted position",
                                position.signalId(), position.symbol(), response.orderId(),
                                response.filledQuantity());
                    } else if ("EXIT_PENDING".equals(status)) {
                        log.warn("LIVE EXIT PENDING RECONCILIATION | signalId={} | symbol={} | orderId={}",
                                position.signalId(), position.symbol(), response.orderId());
                    } else {
                        log.warn("LIVE EXIT RESPONSE REQUIRES REVIEW | signalId={} | symbol={} | "
                                        + "status={} | success={} | message={}",
                                position.signalId(), position.symbol(), status,
                                response.success(), response.message());
                    }
                })
                .doFinally(signal -> invalidateOpenTrades())
                .then();
    }

    private boolean shouldExtendTarget(
            PaperTrade trade,
            Tick tick) {

        if (!dynamicTargetEnabled) {
            return false;
        }

        if (trade == null ||
                tick == null ||
                trade.getTargetPrice() == null) {

            return false;
        }

        if (Boolean.TRUE.equals(
                trade.getTargetExtended())) {

            return false;
        }

        LocalTime marketClose =
                squareOffTime;

        LocalTime currentTime =
                LocalTime.now(
                        java.time.ZoneId.of("Asia/Kolkata"));

        long minutesRemaining =
                java.time.Duration.between(
                                currentTime,
                                marketClose)
                        .toMinutes();

        if (minutesRemaining <
                minMinutesRemainingForTargetExtension) {

            return false;
        }

        double currentPrice =
                tick.getLastTradedPrice();

        boolean isBuy =
                SignalType.BUY.name()
                        .equalsIgnoreCase(
                                trade.getSignal());

        return isBuy
                ? currentPrice >= trade.getTargetPrice()
                : currentPrice <= trade.getTargetPrice();
    }

    private Mono<PaperTrade> extendTarget(
            PaperTrade trade,
            Tick tick) {

        double oldTarget =
                trade.getTargetPrice();


        /*
         * ============================================================
         * PRESERVE ORIGINAL TARGET
         * ============================================================
         *
         * Store the original target only once.
         *
         * Example:
         *
         * Entry  = 100
         * Target = 102
         *
         * originalTargetPrice = 102
         */
        if (trade.getOriginalTargetPrice() == null) {

            trade.setOriginalTargetPrice(
                    oldTarget);
        }


        /*
         * ============================================================
         * CALCULATE TARGET EXTENSION
         * ============================================================
         *
         * Example:
         *
         * Entry  = 100
         * Target = 102
         * Distance = 2
         *
         * 25% extension = 0.50
         *
         * BUY  -> 102.50
         * SELL -> 97.50
         */
        double extension =
                Math.abs(
                        oldTarget -
                                trade.getEntryPrice())
                        * (targetExtensionPercent / 100.0);


        boolean isBuy =
                SignalType.BUY.name()
                        .equalsIgnoreCase(
                                trade.getSignal());


        double newTarget =
                isBuy
                        ? oldTarget + extension
                        : oldTarget - extension;


        /*
         * ============================================================
         * ROUND TARGET
         * ============================================================
         */

        newTarget =
                Math.round(
                        newTarget * 100.0)
                        / 100.0;


        /*
         * ============================================================
         * UPDATE TARGET
         * ============================================================
         */

        trade.setTargetPrice(
                newTarget);


        /*
         * ============================================================
         * MARK TARGET AS EXTENDED
         * ============================================================
         */

        trade.setTargetExtended(
                true);


        /*
         * ============================================================
         * STORE EXTENSION TIME
         * ============================================================
         */

        trade.setTargetExtendedTime(
                LocalDateTime.now());


        /*
         * ============================================================
         * STORE EXTENSION REASON
         * ============================================================
         */

        trade.setTargetExtensionReason(
                "TARGET_REACHED_WITH_SUFFICIENT_TIME");


        /*
         * ============================================================
         * LOG TARGET EXTENSION
         * ============================================================
         */

        log.info(
                "==================================================");

        log.info(
                "TARGET EXTENDED");

        log.info(
                "TradeId          : {}",
                trade.getId());

        log.info(
                "Symbol           : {}",
                trade.getSymbol());

        log.info(
                "Signal           : {}",
                trade.getSignal());

        log.info(
                "Entry Price      : {}",
                trade.getEntryPrice());

        log.info(
                "Original Target  : {}",
                trade.getOriginalTargetPrice());

        log.info(
                "Old Target       : {}",
                oldTarget);

        log.info(
                "Extension        : {}",
                extension);

        log.info(
                "Extension %      : {}",
                targetExtensionPercent);

        log.info(
                "New Target       : {}",
                newTarget);

        log.info(
                "Current Price    : {}",
                tick.getLastTradedPrice());

        log.info(
                "Extension Reason : {}",
                trade.getTargetExtensionReason());

        log.info(
                "Extension Time   : {}",
                trade.getTargetExtendedTime());

        log.info(
                "==================================================");


        /*
         * ============================================================
         * SAVE PAPER TRADE
         * ============================================================
         *
         * JPA repository is blocking, therefore execute it on
         * boundedElastic.
         */

        return Mono.fromCallable(
                        () -> paperTradeRepository.save(trade))
                .subscribeOn(
                        Schedulers.boundedElastic());
    }


    /*
     * ================================================================
     * MAP PAPER TRADE -> OPEN POSITION
     * ================================================================
     */

    private OpenPosition map(
            PaperTrade trade) {

        return OpenPosition.builder()

                .symbol(
                        trade.getSymbol())

                .signal(
                        trade.getSignal())

                .entryPrice(
                        trade.getEntryPrice())

                .targetPrice(
                        trade.getTargetPrice())

                .stopLoss(
                        trade.getCurrentStopLoss())

                .initialStopLoss(
                        trade.getInitialStopLoss())

                .quantity(
                        trade.getQuantity())

                .build();
    }
}