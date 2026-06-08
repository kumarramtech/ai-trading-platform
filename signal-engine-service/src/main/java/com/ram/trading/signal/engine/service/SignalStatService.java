package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.SignalStats;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.repo.TradingSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SignalStatService {
    private final TradingSignalRepository repository;

    public SignalStats getStats() {

        List<TradingSignalEntity> signals =
                repository.findAll();

        long totalSignals = signals.size();

        long openSignals =
                signals.stream()
                        .filter(s ->
                                SignalStatus.OPEN
                                        .equals(s.getStatus()))
                        .count();

        long winningSignals =
                signals.stream()
                        .filter(s ->
                                SignalStatus.TARGET_HIT
                                        .equals(s.getStatus()))
                        .count();

        long losingSignals =
                signals.stream()
                        .filter(s ->
                                SignalStatus.STOP_LOSS_HIT
                                        .equals(s.getStatus()))
                        .count();

        double winRate =
                (winningSignals + losingSignals) == 0
                        ? 0
                        : (winningSignals * 100.0)
                        / (winningSignals + losingSignals);

        double totalProfit =
                signals.stream()
                        .filter(s ->
                                s.getProfitLoss() != null)
                        .mapToDouble(
                                TradingSignalEntity::getProfitLoss)
                        .sum();

        return SignalStats.builder()
                .totalSignals(totalSignals)
                .openSignals(openSignals)
                .losingSignals(losingSignals)
                .winningSignals(winningSignals)
                .winRate(winRate)
                .totalProfit(totalProfit)
                .build();
    }
}
