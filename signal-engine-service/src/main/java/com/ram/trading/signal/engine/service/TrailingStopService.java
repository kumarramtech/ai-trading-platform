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

@Service
@RequiredArgsConstructor
@Slf4j
public class TrailingStopService {
    private final PaperTradeRepository repository;
    private final NotificationClient notificationClient;

    public Mono<PaperTrade> updateTrailingStop(
            PaperTrade trade,
            Tick tick) {

        double currentPrice = tick.getLastTradedPrice();

        if (!isEligibleForTrailing(trade, currentPrice)) {
            return Mono.just(trade);
        }

        double previousStop = trade.getCurrentStopLoss();
        double newStop = calculateNewStop(trade, currentPrice);

        if (Double.compare(previousStop, newStop) == 0) {
            return Mono.just(trade);
        }

        updateTrade(trade, newStop);

        return Mono.fromCallable(() -> repository.save(trade))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(savedTrade ->
                        sendTrailingNotification(savedTrade, previousStop, newStop)
                                .thenReturn(savedTrade))
                .doOnSuccess(saved ->
                        log.info("Trailing Stop Updated : Symbol={} Old={} New={}",
                                saved.getSymbol(),
                                previousStop,
                                newStop))
                .onErrorResume(ex -> {
                    log.error("Error updating trailing stop for {}",
                            trade.getSymbol(),
                            ex);
                    return Mono.just(trade);
                });
    }

    private boolean isEligibleForTrailing(
            PaperTrade trade,
            double currentPrice) {

        if (trade.getCurrentStopLoss() == null
                || trade.getInitialStopLoss() == null) {
            return false;
        }

        double entry = trade.getEntryPrice();
        double initialStop = trade.getInitialStopLoss();

        double risk = Math.abs(entry - initialStop);

        if (risk <= 0) {
            return false;
        }

        int currentStep;

        if (SignalType.BUY.name().equalsIgnoreCase(trade.getSignal())) {

            if (currentPrice <= entry) {
                return false;
            }

            currentStep = (int) ((currentPrice - entry) / risk);

        } else {

            if (currentPrice >= entry) {
                return false;
            }

            currentStep = (int) ((entry - currentPrice) / risk);
        }

        int trailingStep = trade.getTrailingStep() == null
                ? 0
                : trade.getTrailingStep();

        return currentStep > trailingStep;
    }

    private double calculateNewStop(
            PaperTrade trade,
            double currentPrice) {

        double entry = trade.getEntryPrice();
        double initialStop = trade.getInitialStopLoss();

        double risk = Math.abs(entry - initialStop);

        int step;

        if (SignalType.BUY.name().equalsIgnoreCase(trade.getSignal())) {

            step = (int) ((currentPrice - entry) / risk);

            return entry + ((step - 1) * risk);

        } else {

            step = (int) ((entry - currentPrice) / risk);

            return entry - ((step - 1) * risk);
        }
    }

    private void updateTrade(
            PaperTrade trade,
            double newStop) {

        Double previousStop = trade.getCurrentStopLoss();

        trade.setCurrentStopLoss(newStop);

        trade.setTrailingStep(
                trade.getTrailingStep() == null
                        ? 1
                        : trade.getTrailingStep() + 1);

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
                trade.getTrailingStep());
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
