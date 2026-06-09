package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.PaperTradeSummary;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaperTradingService {

    private final PaperTradeRepository repository;

    public void createTrade(
            TradingSignalEntity signal) {

        System.out.println(
                "Signal Generated = "
                        + signal.getSignal());

        System.out.println(
                "Inside createTrade");

        if (SignalType.HOLD.name()
                .equals(signal.getSignal())) {
            return;
        }

        double capitalPerTrade = 10000;

        int quantity =
                (int) (capitalPerTrade
                        / signal.getEntryPrice());

        System.out.println(
                quantity);
        if (quantity <= 0) {
            return;
        }

        PaperTrade trade =
                PaperTrade.builder()
                        .symbol(signal.getSymbol())
                        .signalId(signal.getId())
                        .signal(signal.getSignal())
                        .entryPrice(signal.getEntryPrice())
                        .quantity(quantity)
                        .investedAmount(
                                quantity *
                                        signal.getEntryPrice())
                        .status(SignalStatus.OPEN)
                        .entryTime(LocalDateTime.now())
                        .build();

        repository.save(trade);
    }

    public List<PaperTrade> getByStatus(SignalStatus status) {

        return repository.findByStatus(status);
    }

    public List<PaperTrade> getAll() {
        return repository.findAll();
    }

    public PaperTradeSummary getSummary() {

        List<PaperTrade> trades =
                repository.findAll();

        long totalTrades =
                trades.size();

        long openPositions =
                trades.stream()
                        .filter(t ->
                                SignalStatus.OPEN
                                        .equals(t.getStatus()))
                        .count();

        long closedPositions =
                totalTrades -
                        openPositions;

        double totalInvestment =
                trades.stream()
                        .mapToDouble(
                                PaperTrade::getInvestedAmount)
                        .sum();

        double totalProfit =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        long winningTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .count();

        double winRate =
                (winningTrades + losingTrades) == 0
                        ? 0
                        : (winningTrades * 100.0)
                        / (winningTrades + losingTrades);

        return PaperTradeSummary.builder()
                .totalTrades(totalTrades)
                .openPositions(openPositions)
                .closedPositions(closedPositions)
                .totalInvestment(totalInvestment)
                .totalProfit(totalProfit)
                .winRate(winRate)
                .build();
    }

    public void closeTrade(
            String symbol,
            Double exitPrice,
            SignalStatus status) {

        PaperTrade trade =
                repository
                        .findTopBySymbolAndStatusOrderByEntryTimeDesc(
                                symbol,
                                SignalStatus.OPEN)
                        .orElse(null);

        if (trade == null) {
            return;
        }

        trade.setExitPrice(exitPrice);

        double profitLoss =
                (exitPrice -
                        trade.getEntryPrice())
                        * trade.getQuantity();

        trade.setProfitLoss(profitLoss);

        trade.setStatus(status);

        trade.setExitTime(
                LocalDateTime.now());

        repository.save(trade);
    }
}