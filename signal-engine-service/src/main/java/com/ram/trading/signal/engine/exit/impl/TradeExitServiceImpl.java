package com.ram.trading.signal.engine.exit.impl;

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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExitServiceImpl implements TradeExitService {

    private final PaperTradeRepository paperTradeRepository;

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


    @Override
    public Mono<Void> evaluateExit(Tick tick) {

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
                                                ExitReason.STRUCTURE_FAILURE)
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
                LocalTime.of(15, 30);

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