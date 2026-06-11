package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.PaperTradeSummary;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradePerformance;
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

    private final IndicatorClient indicatorClient;

    public void createTrade(
            TradingSignalEntity signal,TechnicalIndicatorResponse indicatorResponse) {

        if (indicatorResponse == null) {
            throw new RuntimeException(
                    "Technical indicators not found for "
                            + signal.getSymbol());
        }

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
        System.out.println("Confidence = " + signal.getConfidence());
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
                        .rsi(indicatorResponse.getRsi14())
                        .ema20(indicatorResponse.getEma20())
                        .ema50(indicatorResponse.getEma50())
                        .macd(indicatorResponse.getMacd())
                        .status(SignalStatus.OPEN)
                        .confidence(signal.getConfidence())
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
            Long signalId,
            Double exitPrice,
            SignalStatus status) {

        PaperTrade trade =
                repository
                        .findBySignalIdAndStatus(
                                signalId,
                                SignalStatus.OPEN)
                        .orElse(null);

        if (trade == null) {
            return;
        }

        trade.setExitPrice(exitPrice);

        double profitLoss;

        if (SignalType.SELL.name()
                .equals(trade.getSignal())) {

            profitLoss =
                    (trade.getEntryPrice()
                            - exitPrice)
                            * trade.getQuantity();

        } else {

            profitLoss =
                    (exitPrice
                            - trade.getEntryPrice())
                            * trade.getQuantity();
        }

        trade.setProfitLoss(profitLoss);

        trade.setStatus(status);

        trade.setExitTime(
                LocalDateTime.now());

        System.out.println(
                "Closing Trade for signalId="
                        + signalId);

        System.out.println(
                "Trade Found="
                        + trade.getId());

        System.out.println(
                "Profit/Loss="
                        + profitLoss);

        repository.save(trade);
    }

    public TradePerformance getPerformance() {

        List<PaperTrade> trades =
                repository.findAll();

        long totalTrades = trades.size();

        List<PaperTrade> closedTrades =
                trades.stream()
                        .filter(t ->
                                t.getStatus() != SignalStatus.OPEN)
                        .toList();

        long winningTrades =
                closedTrades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                closedTrades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .count();

        double averageProfit =
                closedTrades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .average()
                        .orElse(0);

        double averageLoss =
                closedTrades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .average()
                        .orElse(0);

        double bestTrade =
                closedTrades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .max()
                        .orElse(0);

        double worstTrade =
                closedTrades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .min()
                        .orElse(0);

        double winRate =
                closedTrades.isEmpty()
                        ? 0
                        : ((double) winningTrades
                        / closedTrades.size()) * 100;

        return TradePerformance.builder()
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .averageProfit(averageProfit)
                .averageLoss(averageLoss)
                .bestTrade(bestTrade)
                .worstTrade(worstTrade)
                .winRate(winRate)
                .build();
    }

    public List<PaperTrade> getHistory() {

        return repository
                .findAllByOrderByEntryTimeDesc();
    }
}