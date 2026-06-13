package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.AIServiceClient;
import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.*;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    private final PaperTradeRepository repository;

    private final AIServiceClient aiServiceClient;

    public void createTrade(
            TradingSignalEntity signal,TechnicalIndicatorResponse indicatorResponse) {

        if (indicatorResponse == null) {
            throw new RuntimeException(
                    "Technical indicators not found for "
                            + signal.getSymbol());
        }

        log.info(
                "Signal Generated = "
                        + signal.getSignal());

        log.info(
                "Inside createTrade");

        if (SignalType.HOLD.name()
                .equals(signal.getSignal())) {
            return;
        }

        double capitalPerTrade = 10000;

        int quantity =
                (int) (capitalPerTrade
                        / signal.getEntryPrice());

        log.info("quantity ="+quantity);
        if (quantity <= 0) {
            return;
        }
        log.info("Confidence = " + signal.getConfidence());
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

        log.info(
                "Closing Trade for signalId="
                        + signalId);

        log.info(
                "Trade Found="
                        + trade.getId());

        log.info(
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

    public TradeInsights getInsights() {
        List<PaperTrade> closedTrades =
                repository.findAll()
                        .stream()
                        .filter(trade ->
                                trade.getStatus() != SignalStatus.OPEN)
                        .toList();

        double averageWinningRsi =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() > 0)
                        .mapToDouble(PaperTrade::getRsi)
                        .average()
                        .orElse(0);

        double averageLosingRsi =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() < 0)
                        .mapToDouble(PaperTrade::getRsi)
                        .average()
                        .orElse(0);

        double averageWinningMacd =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() > 0)
                        .mapToDouble(PaperTrade::getMacd)
                        .average()
                        .orElse(0);

        double averageLosingMacd =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() < 0)
                        .mapToDouble(PaperTrade::getMacd)
                        .average()
                        .orElse(0);

        Integer bestConfidence =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getConfidence() != null)
                        .map(PaperTrade::getConfidence)
                        .max(Integer::compareTo)
                        .orElse(0);

        Integer worstConfidence =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getConfidence() != null)
                        .map(PaperTrade::getConfidence)
                        .min(Integer::compareTo)
                        .orElse(0);

        return TradeInsights.builder()
                .averageWinningRsi(
                        averageWinningRsi)
                .averageLosingRsi(
                        averageLosingRsi)
                .averageWinningMacd(
                        averageWinningMacd)
                .averageLosingMacd(
                        averageLosingMacd)
                .bestConfidence(
                        bestConfidence)
                .worstConfidence(
                        worstConfidence)
                .build();
    }

    public PaperTradeDashboard getDashboard() {

        List<PaperTrade> trades =
                repository.findAll();

        long totalTrades =
                trades.size();

        long openTrades =
                trades.stream()
                        .filter(t ->
                                t.getStatus() ==
                                        SignalStatus.OPEN)
                        .count();

        List<PaperTrade> closedTradesList =
                trades.stream()
                        .filter(t ->
                                t.getStatus() !=
                                        SignalStatus.OPEN)
                        .toList();

        long closedTrades =
                closedTradesList.size();

        long winningTrades =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .count();

        long breakevenTrades =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() == 0)
                        .count();

        double totalProfit =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        double bestTrade =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .max()
                        .orElse(0);

        double worstTrade =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .min()
                        .orElse(0);

        double winRate =
                closedTrades == 0
                        ? 0
                        : ((double) winningTrades
                        / closedTrades) * 100;

        return PaperTradeDashboard.builder()
                .totalTrades(totalTrades)
                .openTrades(openTrades)
                .closedTrades(closedTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .breakevenTrades(breakevenTrades)
                .winRate(winRate)
                .totalProfit(totalProfit)
                .bestTrade(bestTrade)
                .worstTrade(worstTrade)
                .build();
    }

    public StrategyReport getStrategyReport() {
        List<PaperTrade> trades =
                repository.findAll()
                        .stream()
                        .filter(t ->
                                t.getStatus() != SignalStatus.OPEN)
                        .toList();

        long totalTrades =
                trades.size();

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
                totalTrades == 0
                        ? 0
                        : ((double) winningTrades
                        / totalTrades) * 100;

        double averageConfidence =
                trades.stream()
                        .filter(t ->
                                t.getConfidence() != null)
                        .mapToInt(
                                PaperTrade::getConfidence)
                        .average()
                        .orElse(0);

        double averageWinningConfidence =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0
                                        && t.getConfidence() != null)
                        .mapToInt(
                                PaperTrade::getConfidence)
                        .average()
                        .orElse(0);

        double averageLosingConfidence =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0
                                        && t.getConfidence() != null)
                        .mapToInt(
                                PaperTrade::getConfidence)
                        .average()
                        .orElse(0);

        double averageWinningRsi =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0
                                        && t.getRsi() != null)
                        .mapToDouble(
                                PaperTrade::getRsi)
                        .average()
                        .orElse(0);

        double averageLosingRsi =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0
                                        && t.getRsi() != null)
                        .mapToDouble(
                                PaperTrade::getRsi)
                        .average()
                        .orElse(0);

        long breakevenTrades =  trades.stream().filter(t ->t.getProfitLoss() != null
                                        && t.getProfitLoss() == 0).count();
        return StrategyReport.builder()
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(winRate)
                .averageConfidence(averageConfidence)
                .averageWinningConfidence(averageWinningConfidence)
                .averageLosingConfidence(averageLosingConfidence)
                .averageWinningRsi(averageWinningRsi)
                .averageLosingRsi(averageLosingRsi)
                .breakevenTrades(breakevenTrades)
                .build();
    }

    public Mono<TradeReviewResponse> reviewTrade(
            Long tradeId) {

        PaperTrade trade =
                repository.findById(tradeId)
                        .orElseThrow();

        if (trade.getExitPrice() == null ||
                trade.getProfitLoss() == null) {

            return Mono.just(
                    TradeReviewResponse.builder()
                            .tradeId(trade.getId())
                            .review(
                                    "Trade is still OPEN. AI review will be available once the trade is completed.")
                            .build());
        }

        TradeReviewRequest request =
                TradeReviewRequest.builder()
                        .tradeId(trade.getId())
                        .symbol(trade.getSymbol())
                        .signal(trade.getSignal())
                        .entryPrice(trade.getEntryPrice())
                        .exitPrice(trade.getExitPrice())
                        .profitLoss(trade.getProfitLoss())
                        .confidence(trade.getConfidence())
                        .rsi(trade.getRsi())
                        .ema20(trade.getEma20())
                        .ema50(trade.getEma50())
                        .macd(trade.getMacd())
                        .build();

        return aiServiceClient.reviewTrade(
                request);
    }

    public Mono<StrategyReviewResponse> strategyReview() {

        List<PaperTrade> trades =
                repository
                        .findTop20ByStatusNotOrderByIdDesc(
                                SignalStatus.OPEN);

        List<TradeReviewRequest> requests =
                trades.stream()
                        .map(trade ->
                                TradeReviewRequest.builder()
                                        .tradeId(trade.getId())
                                        .symbol(trade.getSymbol())
                                        .signal(trade.getSignal())
                                        .entryPrice(trade.getEntryPrice())
                                        .exitPrice(trade.getExitPrice())
                                        .profitLoss(trade.getProfitLoss())
                                        .confidence(trade.getConfidence())
                                        .rsi(trade.getRsi())
                                        .ema20(trade.getEma20())
                                        .ema50(trade.getEma50())
                                        .macd(trade.getMacd())
                                        .build())
                        .toList();
                if (requests.isEmpty()) {
                    return Mono.just(
                            StrategyReviewResponse.builder()
                                    .totalTrades(0)
                                    .winningTrades(0)
                                    .losingTrades(0)
                                    .review("No completed trades available for strategy review.")
                                    .build());
                }
        return aiServiceClient
                .reviewStrategy(requests);
    }

}