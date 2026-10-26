package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrailingStopService {

    /*
     * ============================================================
     * PROFIT / R-MULTIPLE TRAILING CONFIGURATION
     * ============================================================
     *
     * R = Initial Risk per Trade
     *
     * Example:
     *
     * Entry       = 100
     * Initial SL  = 96
     *
     * Risk = 100 - 96 = 4
     *
     * Therefore:
     *
     * 0.50R = 2 profit
     * 0.75R = 3 profit
     * 1.00R = 4 profit
     * 1.50R = 6 profit
     * 2.00R = 8 profit
     * 3.00R = 12 profit
     *
     * This makes trailing independent of:
     *
     * - market time
     * - fixed percentage
     * - symbol price
     *
     * The stop is driven by actual trade profitability.
     *
     * Break-even arms at 1.00R (not 0.50R): stops are clamped to the
     * opening range, so R is often only ~0.4% of price and 0.50R was
     * ordinary intraday noise, pinning trades at entry within minutes.
     *
     * MIN_TRAIL_MOVE_PCT additionally requires a minimum favorable move
     * in price terms before any trailing, so a very tight initial stop
     * cannot arm break-even on a few ticks.
     */

    private static final double MIN_TRAIL_MOVE_PCT = 0.50;

    private static final double BREAK_EVEN_R = 1.00;

    private static final double STAGE_2_TRIGGER_R = 1.25;
    private static final double STAGE_2_LOCK_R = 0.25;

    private static final double STAGE_3_TRIGGER_R = 1.50;
    private static final double STAGE_3_LOCK_R = 0.50;

    private static final double STAGE_4_TRIGGER_R = 2.00;
    private static final double STAGE_4_LOCK_R = 1.00;

    private static final double STAGE_5_TRIGGER_R = 2.50;
    private static final double STAGE_5_LOCK_R = 1.50;

    private static final double STAGE_6_TRIGGER_R = 3.00;
    private static final double STAGE_6_LOCK_R = 2.25;

    private final PaperTradeRepository repository;
    private final NotificationClient notificationClient;



    /**
     * Calculates a live-position trailing stop using the same R-multiple stages
     * as paper trading. This method is deliberately pure: it never reads or
     * writes PaperTrade persistence and never calls the broker.
     *
     * @return a strictly improved stop rounded to paise, or empty when no update
     *         is warranted or the position data is invalid.
     */
    public Optional<BigDecimal> calculateLiveTrailingStop(
            String side,
            BigDecimal entryPrice,
            BigDecimal initialStopLoss,
            BigDecimal currentStopLoss,
            double currentPrice) {

        if (side == null || entryPrice == null || initialStopLoss == null
                || currentStopLoss == null || !Double.isFinite(currentPrice)
                || currentPrice <= 0 || entryPrice.signum() <= 0
                || initialStopLoss.signum() <= 0 || currentStopLoss.signum() <= 0) {
            return Optional.empty();
        }

        boolean isBuy = "BUY".equalsIgnoreCase(side.trim());
        boolean isSell = "SELL".equalsIgnoreCase(side.trim());
        if (!isBuy && !isSell) {
            return Optional.empty();
        }

        double entry = entryPrice.doubleValue();
        double initialStop = initialStopLoss.doubleValue();
        double currentStop = currentStopLoss.doubleValue();
        double initialRisk = isBuy ? entry - initialStop : initialStop - entry;
        if (!Double.isFinite(initialRisk) || initialRisk <= 0) {
            return Optional.empty();
        }

        double currentProfit = isBuy ? currentPrice - entry : entry - currentPrice;
        double currentR = currentProfit / initialRisk;
        int step = calculateTrailingStep(currentR, currentProfit / entry * 100.0d);
        if (step == 0) {
            return Optional.empty();
        }

        double candidate;
        if (step == 1) {
            candidate = entry;
        } else {
            double lockedR = switch (step) {
                case 2 -> STAGE_2_LOCK_R;
                case 3 -> STAGE_3_LOCK_R;
                case 4 -> STAGE_4_LOCK_R;
                case 5 -> STAGE_5_LOCK_R;
                case 6 -> STAGE_6_LOCK_R;
                default -> 0.0d;
            };
            candidate = isBuy
                    ? entry + initialRisk * lockedR
                    : entry - initialRisk * lockedR;
        }

        if (!Double.isFinite(candidate) || candidate <= 0) {
            return Optional.empty();
        }

        BigDecimal rounded = BigDecimal.valueOf(candidate).setScale(2, RoundingMode.HALF_UP);
        int comparison = rounded.compareTo(currentStopLoss);
        if ((isBuy && comparison <= 0) || (isSell && comparison >= 0)) {
            return Optional.empty();
        }
        return Optional.of(rounded);
    }

    /**
     * Called from TradeExitService on every market tick.
     *
     * Responsibilities:
     *
     * 1. Check whether trade has enough profit for trailing.
     * 2. Calculate R-multiple.
     * 3. Determine profit protection stage.
     * 4. Calculate improved stop.
     * 5. Never move BUY stop downward.
     * 6. Never move SELL stop upward.
     * 7. Persist the updated stop.
     * 8. Send notification.
     */
    public Mono<PaperTrade> updateTrailingStop(
            PaperTrade trade,
            Tick tick) {

        /*
         * --------------------------------------------------------
         * BASIC VALIDATION
         * --------------------------------------------------------
         */
        if (trade == null || tick == null) {

            log.warn(
                    "Skipping trailing stop update due to null trade or tick.");

            return Mono.empty();
        }

        if (trade.getCurrentStopLoss() == null
                || trade.getInitialStopLoss() == null
                || trade.getEntryPrice() == null) {

            log.warn(
                    "Skipping trailing stop update due to incomplete trade data. Symbol={}",
                    trade.getSymbol());

            return Mono.just(trade);
        }

        double currentPrice = tick.getLastTradedPrice();

        if (currentPrice <= 0) {

            log.warn(
                    "Skipping trailing stop update due to invalid price. Symbol={} Price={}",
                    trade.getSymbol(),
                    currentPrice);

            return Mono.just(trade);
        }

        /*
         * --------------------------------------------------------
         * DETERMINE DIRECTION
         * --------------------------------------------------------
         */
        boolean isBuy = SignalType.BUY.name()
                .equalsIgnoreCase(trade.getSignal());

        boolean isSell = SignalType.SELL.name()
                .equalsIgnoreCase(trade.getSignal());

        if (!isBuy && !isSell) {

            log.warn(
                    "Skipping trailing stop update due to unknown signal. Symbol={} Signal={}",
                    trade.getSymbol(),
                    trade.getSignal());

            return Mono.just(trade);
        }

        /*
         * --------------------------------------------------------
         * CALCULATE INITIAL RISK
         * --------------------------------------------------------
         *
         * BUY:
         *
         * Risk = Entry - Initial SL
         *
         * SELL:
         *
         * Risk = Initial SL - Entry
         */
        double entryPrice = trade.getEntryPrice();
        double initialStopLoss = trade.getInitialStopLoss();

        double initialRisk;

        if (isBuy) {

            initialRisk = entryPrice - initialStopLoss;

        } else {

            initialRisk = initialStopLoss - entryPrice;
        }

        /*
         * Invalid initial risk means we cannot calculate R.
         */
        if (initialRisk <= 0) {

            log.warn(
                    "Skipping trailing stop update due to invalid initial risk. " +
                            "Symbol={} Signal={} Entry={} InitialSL={} Risk={}",
                    trade.getSymbol(),
                    trade.getSignal(),
                    entryPrice,
                    initialStopLoss,
                    initialRisk);

            return Mono.just(trade);
        }

        /*
         * --------------------------------------------------------
         * CALCULATE CURRENT PROFIT
         * --------------------------------------------------------
         *
         * This is per share.
         */
        double currentProfit;

        if (isBuy) {

            currentProfit = currentPrice - entryPrice;

        } else {

            currentProfit = entryPrice - currentPrice;
        }

        /*
         * --------------------------------------------------------
         * CALCULATE R-MULTIPLE
         * --------------------------------------------------------
         *
         * R = Current Profit / Initial Risk
         *
         * Example:
         *
         * Initial Risk = ₹10
         * Current Profit = ₹20
         *
         * R = 20 / 10 = 2R
         */
        double currentR = currentProfit / initialRisk;

        /*
         * --------------------------------------------------------
         * DETERMINE TRAILING STAGE
         * --------------------------------------------------------
         */
        int currentStep = calculateTrailingStep(
                currentR,
                currentProfit / entryPrice * 100.0d);

        /*
         * No trailing until 1.00R and the minimum favorable move.
         */
        if (currentStep == 0) {

            return Mono.just(trade);
        }

        /*
         * --------------------------------------------------------
         * CALCULATE PREVIOUS STOP
         * --------------------------------------------------------
         */
        double previousStop = trade.getCurrentStopLoss();

        /*
         * --------------------------------------------------------
         * CALCULATE NEW STOP
         * --------------------------------------------------------
         */
        double newStop = calculateNewStop(
                trade,
                currentR,
                initialRisk,
                currentStep);

        /*
         * --------------------------------------------------------
         * SAFETY RULE
         * --------------------------------------------------------
         *
         * BUY:
         * New SL must be HIGHER than existing SL.
         *
         * SELL:
         * New SL must be LOWER than existing SL.
         *
         * This prevents the trailing engine from weakening
         * an already-protected position.
         */
        boolean stopNotImproved;

        if (isBuy) {

            stopNotImproved = newStop <= previousStop;

        } else {

            stopNotImproved = newStop >= previousStop;
        }

        if (stopNotImproved) {

            log.debug(
                    "Trailing Stop Not Improved | " +
                            "Symbol={} Signal={} Price={} " +
                            "CurrentR={} CurrentStop={} CalculatedStop={} Step={}",
                    trade.getSymbol(),
                    trade.getSignal(),
                    currentPrice,
                    currentR,
                    previousStop,
                    newStop,
                    currentStep);

            return Mono.just(trade);
        }

        /*
         * --------------------------------------------------------
         * UPDATE TRADE
         * --------------------------------------------------------
         */
        updateTrade(
                trade,
                newStop,
                currentStep);

        /*
         * --------------------------------------------------------
         * PERSIST
         * --------------------------------------------------------
         */
        return Mono.fromCallable(
                        () -> repository.save(trade))

                .subscribeOn(Schedulers.boundedElastic())

                .flatMap(savedTrade ->
                        sendTrailingNotification(
                                savedTrade,
                                previousStop,
                                newStop,
                                currentR,
                                currentStep)
                                .thenReturn(savedTrade))

                .doOnSuccess(saved ->

                        log.info(
                                "Trailing Stop Updated Successfully | " +
                                        "Symbol={} | Signal={} | " +
                                        "Price={} | Entry={} | " +
                                        "CurrentR={} | " +
                                        "OldStop={} | NewStop={} | Step={}",
                                saved.getSymbol(),
                                saved.getSignal(),
                                currentPrice,
                                saved.getEntryPrice(),
                                currentR,
                                previousStop,
                                newStop,
                                currentStep))

                .onErrorResume(ex -> {

                    log.error(
                            "Error updating trailing stop for {}",
                            trade.getSymbol(),
                            ex);

                    return Mono.just(trade);
                });
    }


    /**
     * Determines the profit protection stage.
     *
     * Step 0:
     * No trailing (below 1.00R or below MIN_TRAIL_MOVE_PCT).
     *
     * Step 1:
     * 1.00R -> Break-even.
     *
     * Step 2:
     * 1.25R -> Lock 0.25R.
     *
     * Step 3:
     * 1.50R -> Lock 0.50R.
     *
     * Step 4:
     * 2.00R -> Lock 1.00R.
     *
     * Step 5:
     * 2.50R -> Lock 1.50R.
     *
     * Step 6:
     * 3.00R -> Lock 2.25R.
     */
    private int calculateTrailingStep(
            double currentR,
            double profitPct) {

        if (currentR < BREAK_EVEN_R
                || profitPct < MIN_TRAIL_MOVE_PCT) {

            return 0;
        }

        if (currentR < STAGE_2_TRIGGER_R) {

            return 1;
        }

        if (currentR < STAGE_3_TRIGGER_R) {

            return 2;
        }

        if (currentR < STAGE_4_TRIGGER_R) {

            return 3;
        }

        if (currentR < STAGE_5_TRIGGER_R) {

            return 4;
        }

        if (currentR < STAGE_6_TRIGGER_R) {

            return 5;
        }

        return 6;
    }


    /**
     * Calculates the new stop based on R-multiple.
     */
    private double calculateNewStop(
            PaperTrade trade,
            double currentR,
            double initialRisk,
            int currentStep) {

        double entryPrice = trade.getEntryPrice();

        boolean isBuy = SignalType.BUY.name()
                .equalsIgnoreCase(trade.getSignal());

        /*
         * --------------------------------------------------------
         * STEP 1
         * --------------------------------------------------------
         *
         * 1.00R profit
         *
         * Move stop to entry.
         *
         * This eliminates the original risk once the trade
         * has demonstrated enough favorable movement.
         */
        if (currentStep == 1) {

            return entryPrice;
        }

        /*
         * --------------------------------------------------------
         * DETERMINE LOCKED R
         * --------------------------------------------------------
         */
        double lockedR;

        switch (currentStep) {

            case 2:
                lockedR = STAGE_2_LOCK_R;
                break;

            case 3:
                lockedR = STAGE_3_LOCK_R;
                break;

            case 4:
                lockedR = STAGE_4_LOCK_R;
                break;

            case 5:
                lockedR = STAGE_5_LOCK_R;
                break;

            case 6:
                lockedR = STAGE_6_LOCK_R;
                break;

            default:
                return trade.getCurrentStopLoss();
        }

        /*
         * Convert locked R back into actual price distance.
         */
        double lockedProfit = initialRisk * lockedR;

        /*
         * BUY:
         *
         * Entry + locked profit
         *
         * SELL:
         *
         * Entry - locked profit
         */
        if (isBuy) {

            return entryPrice + lockedProfit;

        } else {

            return entryPrice - lockedProfit;
        }
    }


    /**
     * Updates the PaperTrade entity in memory.
     */
    private void updateTrade(
            PaperTrade trade,
            double newStop,
            int currentStep) {

        Double previousStop =
                trade.getCurrentStopLoss();

        trade.setCurrentStopLoss(newStop);

        trade.setTrailingStep(currentStep);

        trade.setLastTrailingUpdate(
                LocalDateTime.now());

        log.info(
                """
                ================================
                TRAILING STOP UPDATED
                ================================
                Symbol       : {}
                Signal       : {}
                Entry        : {}
                Previous SL  : {}
                New SL       : {}
                Step         : {}
                ================================
                """,
                trade.getSymbol(),
                trade.getSignal(),
                trade.getEntryPrice(),
                previousStop,
                newStop,
                currentStep);
    }


    /**
     * Sends notification whenever a new stop is persisted.
     */
    private Mono<Void> sendTrailingNotification(
            PaperTrade trade,
            double previousStop,
            double newStop,
            double currentR,
            int currentStep) {

        String message = String.format(
                """
                🔒 TRAILING STOP UPDATED

                Symbol          : %s
                Signal          : %s
                Entry           : %.2f

                Previous Stop   : %.2f
                New Stop        : %.2f

                Current R       : %.2fR
                Trailing Stage  : %d

                Profit protection has been increased.
                """,
                trade.getSymbol(),
                trade.getSignal(),
                trade.getEntryPrice(),
                previousStop,
                newStop,
                currentR,
                currentStep);

        NotificationRequest request =
                NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK)
                        .title("Trailing Stop Updated")
                        .message(message)
                        .build();

        return notificationClient
                .sendNotification(request)
                .then()

                .doOnError(ex ->
                        log.error(
                                "Failed to send trailing notification",
                                ex))

                .onErrorResume(
                        ex -> Mono.empty());
    }
}