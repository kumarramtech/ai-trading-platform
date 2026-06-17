package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.RiskCheckResponse;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskManagementService {

    private final PaperTradeRepository repository;

    private static final double CAPITAL = 100000;

    private static final double MAX_DAILY_LOSS = 2000;

    private static final double MAX_EXPOSURE_PERCENT = 60;

    private static final int MAX_OPEN_TRADES = 20;

    private static final int MAX_CONSECUTIVE_LOSSES = 3;

    public RiskCheckResponse validateTrade() {

        List<String> violations = new ArrayList<>();

        if (dailyLossExceeded()) {
            violations.add(
                    "Daily loss limit exceeded. Trading halted.");
        }

        if (maxOpenTradesReached()) {
            violations.add(
                    "Maximum open trades reached.");
        }

        if (exposureExceeded()) {
            violations.add(
                    "Capital exposure limit exceeded.");
        }

        if (consecutiveLossesExceeded()) {
            violations.add(
                    "Maximum consecutive losses reached.");
        }

        boolean allowed =
                violations.isEmpty();

        return RiskCheckResponse.builder()
                .allowed(allowed)
                .violations(violations)
                .build();
    }

    private boolean dailyLossExceeded() {

        LocalDate today =
                LocalDate.now();

        double todayPnL =
                repository.findAll()
                        .stream()
                        .filter(trade ->
                                trade.getExitTime() != null)
                        .filter(trade ->
                                trade.getExitTime()
                                        .toLocalDate()
                                        .equals(today))
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        log.info(
                "Today's PnL = {}",
                todayPnL);

        return todayPnL <= -MAX_DAILY_LOSS;
    }

    private boolean maxOpenTradesReached() {

        long openTrades =
                repository.countByStatus(
                        SignalStatus.OPEN);

        log.info(
                "Open Trades = {}",
                openTrades);

        return openTrades >= MAX_OPEN_TRADES;
    }

    private boolean exposureExceeded() {

        double exposure =
                repository.findByStatus(
                                SignalStatus.OPEN)
                        .stream()
                        .mapToDouble(
                                PaperTrade::getInvestedAmount)
                        .sum();

        double maxAllowedExposure =
                CAPITAL
                        * MAX_EXPOSURE_PERCENT
                        / 100;

        log.info(
                "Current Exposure = {}",
                exposure);

        log.info(
                "Exposure={}, MaxAllowedExposure={}, MaxPercent={}",
                exposure,
                maxAllowedExposure,
                MAX_EXPOSURE_PERCENT);

        return exposure >= maxAllowedExposure;
    }

    private boolean consecutiveLossesExceeded() {

        List<PaperTrade> trades =
                repository.findAllByOrderByEntryTimeDesc();

        int consecutiveLosses = 0;

        for (PaperTrade trade : trades) {

            if (trade.getProfitLoss() == null) {
                continue;
            }

            if (trade.getProfitLoss() < 0) {

                consecutiveLosses++;

                if (consecutiveLosses
                        >= MAX_CONSECUTIVE_LOSSES) {

                    log.warn(
                            "Consecutive losses reached = {}",
                            consecutiveLosses);

                    return true;
                }

            } else {

                break;
            }
        }

        return false;
    }
}