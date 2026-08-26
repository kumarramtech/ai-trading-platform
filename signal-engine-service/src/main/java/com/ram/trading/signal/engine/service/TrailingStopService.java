package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.TradingConstants;
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
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrailingStopService {
    private final PaperTradeRepository repository;
    private final NotificationClient notificationClient;

    public Mono<PaperTrade> updateTrailingStop(PaperTrade trade, Tick tick) {

        /*
         * Safety validation.
         *
         * Mono.just(null) is not allowed,
         * so null trade/tick must return Mono.empty().
         */
        if (trade == null || tick == null) {
            log.warn("Skipping trailing stop update due to null trade or tick.");
            return Mono.empty();
        }

        /*
         * Validate required trade data.
         */
        if (trade.getCurrentStopLoss() == null
                || trade.getInitialStopLoss() == null
                || trade.getEntryPrice() == null) {

            log.warn("Skipping trailing stop update due to incomplete trade data. Symbol={}", trade.getSymbol());

            return Mono.just(trade);
        }

        double currentPrice = tick.getLastTradedPrice();

        /*
         * Trailing starts only after the trade moves
         * 0.45% in the favorable direction.
         */
        if (!isEligibleForTrailing(
                trade,
                currentPrice)) {

            return Mono.just(trade);
        }

        /*
         * Calculate the current trailing stage.
         *
         * Step 1 = Break-even
         * Step 2 = Normal trailing
         * Step 3 = Late market trailing
         * Step 4 = Final defensive trailing
         */
        int currentStep = calculateCurrentStep(
                trade,
                currentPrice);

        double previousStop =
                trade.getCurrentStopLoss();

        /*
         * Calculate the new percentage-based stop.
         */
        double newStop = calculateNewStop(
                trade,
                currentPrice);

        /*
         * Safety Rule:
         *
         * BUY  -> Stop Loss can only move upward.
         * SELL -> Stop Loss can only move downward.
         *
         * If the calculated stop does not improve the
         * existing stop, do nothing.
         */
        boolean isBuy = SignalType.BUY.name()
                .equalsIgnoreCase(trade.getSignal());

        boolean stopNotImproved = isBuy
                ? newStop <= previousStop
                : newStop >= previousStop;

        if (stopNotImproved) {

            log.debug(
                    "Trailing Stop Not Improved : " +
                            "Symbol={} Signal={} CurrentPrice={} " +
                            "CurrentStop={} CalculatedStop={} Step={}",
                    trade.getSymbol(),
                    trade.getSignal(),
                    currentPrice,
                    previousStop,
                    newStop,
                    currentStep);

            return Mono.just(trade);
        }

        /*
         * Update trade only after confirming
         * that the new stop is better.
         */
        updateTrade(
                trade,
                newStop,
                currentStep);

        /*
         * Persist the updated trade and then
         * send the trailing stop notification.
         */
        return Mono.fromCallable(() -> repository.save(trade))
                .subscribeOn(Schedulers.boundedElastic())

                .flatMap(savedTrade ->
                        sendTrailingNotification(
                                savedTrade,
                                previousStop,
                                newStop)
                                .thenReturn(savedTrade))

                .doOnSuccess(saved ->
                        log.info(
                                "Trailing Stop Updated Successfully : " +
                                        "Symbol={} Signal={} Price={} " +
                                        "OldStop={} NewStop={} Step={}",
                                saved.getSymbol(),
                                saved.getSignal(),
                                currentPrice,
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

    private boolean isEligibleForTrailing(
            PaperTrade trade,
            double currentPrice) {

        if (trade.getCurrentStopLoss() == null
                || trade.getInitialStopLoss() == null
                || trade.getEntryPrice() == null) {

            return false;
        }

        double entry = trade.getEntryPrice();

        boolean isBuy = SignalType.BUY.name()
                .equalsIgnoreCase(trade.getSignal());

        /*
         * Break-even activation threshold.
         *
         * Trailing starts once price moves
         * 0.45% in the favorable direction.
         */
        double breakEvenTriggerPercent = 0.45 / 100;

        if (isBuy) {

            double triggerPrice =
                    entry * (1 + breakEvenTriggerPercent);

            return currentPrice >= triggerPrice;

        } else {

            double triggerPrice =
                    entry * (1 - breakEvenTriggerPercent);

            return currentPrice <= triggerPrice;
        }
    }

    private int calculateCurrentStep(
            PaperTrade trade,
            double currentPrice) {

        double entry = trade.getEntryPrice();

        boolean isBuy = SignalType.BUY.name()
                .equalsIgnoreCase(trade.getSignal());

        /*
         * Step 0 = Initial Stop Loss
         * Step 1 = Break-even activated
         * Step 2 = Normal percentage trailing
         * Step 3 = Late market trailing
         * Step 4 = Final defensive trailing
         */

        double breakEvenTriggerPercent = 0.45 / 100;

        boolean breakEvenReached = isBuy
                ? currentPrice >= entry * (1 + breakEvenTriggerPercent)
                : currentPrice <= entry * (1 - breakEvenTriggerPercent);

        if (!breakEvenReached) {
            return 0;
        }

        int existingStep = trade.getTrailingStep() == null
                ? 0
                : trade.getTrailingStep();

        /*
         * First successful trailing update
         * is always Break-even.
         */
        if (existingStep == 0) {
            return 1;
        }

        /*
         * After Break-even, determine
         * trailing stage based on market time.
         */
        LocalTime currentTime = LocalTime.now();

        if (currentTime.isBefore(LocalTime.of(14, 0))) {
            return 2;
        }

        if (currentTime.isBefore(LocalTime.of(14, 45))) {
            return 3;
        }

        return 4;
    }

    private double calculateNewStop(
            PaperTrade trade,
            double currentPrice) {

        double entry = trade.getEntryPrice();

        boolean isBuy = SignalType.BUY.name()
                .equalsIgnoreCase(trade.getSignal());

        /*
         * Break-even activation threshold.
         *
         * Once price moves 0.45% in the favorable direction,
         * Stop Loss moves to Entry Price.
         */
        double breakEvenTriggerPercent = 0.45 / 100;

        double triggerPrice = isBuy
                ? entry * (1 + breakEvenTriggerPercent)
                : entry * (1 - breakEvenTriggerPercent);

        boolean breakEvenReached = isBuy
                ? currentPrice >= triggerPrice
                : currentPrice <= triggerPrice;

        if (!breakEvenReached) {
            return trade.getCurrentStopLoss();
        }

        /*
         * First trailing update:
         *
         * Move Stop Loss to Entry Price
         * to protect against loss.
         */
        int trailingStep = trade.getTrailingStep() == null
                ? 0
                : trade.getTrailingStep();

        if (trailingStep == 0) {

            return entry;
        }

        /*
         * After break-even, use a dynamic
         * percentage-based trailing gap.
         *
         * Before 2:00 PM  -> 0.30%
         * 2:00 - 2:45 PM -> 0.15%
         * After 2:45 PM  -> 0.10%
         */
        LocalTime currentTime = LocalTime.now();

        double trailingPercent;

        if (currentTime.isBefore(LocalTime.of(14, 0))) {

            trailingPercent = 0.30 / 100;

        } else if (currentTime.isBefore(LocalTime.of(14, 45))) {

            trailingPercent = 0.15 / 100;

        } else {

            trailingPercent = 0.10 / 100;
        }

        /*
         * BUY:
         * Stop trails below the current favorable price.
         *
         * SELL:
         * Stop trails above the current favorable price.
         */
        if (isBuy) {

            return currentPrice * (1 - trailingPercent);

        } else {

            return currentPrice * (1 + trailingPercent);
        }
    }

    private void updateTrade(
            PaperTrade trade,
            double newStop,
            int currentStep) {

        Double previousStop = trade.getCurrentStopLoss();

        trade.setCurrentStopLoss(newStop);
        trade.setTrailingStep(currentStep);
        trade.setLastTrailingUpdate(LocalDateTime.now());

        log.info("""
        Trailing Stop Updated
        Symbol={}
        Entry={}
        Previous Stop={}
        New Stop={}
        Step={}
        """,
                trade.getSymbol(),
                trade.getEntryPrice(),
                previousStop,
                newStop,
                currentStep);
    }

    private Mono<Void> sendTrailingNotification(
            PaperTrade trade,
            double previousStop,
            double newStop) {

        String message = String.format("""
            🔒 TRAILING STOP UPDATED

            Symbol : %s
            Entry : %.2f
            Current Stop : %.2f
            Previous Stop : %.2f
            New Stop : %.2f
            Step : %d
            """,
                trade.getSymbol(),
                trade.getEntryPrice(),
                newStop,
                previousStop,
                newStop,
                trade.getTrailingStep());

        NotificationRequest request = NotificationRequest.builder()
                .channel(NotificationChannel.SLACK)
                .title("Trailing Stop Updated")
                .message(message)
                .build();

        return notificationClient.sendNotification(request)
                .then()
                .doOnError(ex ->
                        log.error("Failed to send trailing notification", ex))
                .onErrorResume(ex -> Mono.empty());
    }
}
