package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.TradeInsightsResponse;
import com.ram.trading.signal.engine.entity.Opportunity;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.OpportunityRepository;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final PaperTradeRepository paperTradeRepository;
    private final OpportunityRepository opportunityRepository;

    public TradeInsightsResponse getTradeInsights() {

        List<PaperTrade> trades =
                paperTradeRepository.findByStatusIn(
                        List.of(
                                SignalStatus.TARGET_HIT,
                                SignalStatus.STOP_LOSS_HIT));

        if (trades.isEmpty()) {

            return TradeInsightsResponse.builder()
                    .totalClosedTrades(0)
                    .build();
        }

        Map<String, Double> symbolPnL =
                trades.stream()
                        .collect(Collectors.groupingBy(
                                PaperTrade::getSymbol,
                                Collectors.summingDouble(
                                        PaperTrade::getProfitLoss)));

        Map.Entry<String, Double> best =
                symbolPnL.entrySet()
                        .stream()
                        .max(Map.Entry.comparingByValue())
                        .orElse(null);

        Map.Entry<String, Double> worst =
                symbolPnL.entrySet()
                        .stream()
                        .min(Map.Entry.comparingByValue())
                        .orElse(null);

        List<Integer> winningConfidence =
                trades.stream()
                        .filter(t -> t.getProfitLoss() > 0)
                        .map(this::getConfidence)
                        .filter(Objects::nonNull)
                        .toList();

        double avgWinningConfidence =
                winningConfidence.stream()
                        .mapToInt(Integer::intValue)
                        .average()
                        .orElse(0);

        List<PaperTrade> positiveTrades =
                trades.stream()
                        .filter(this::isPositiveSentiment)
                        .toList();

        double positiveWinRate =
                positiveTrades.isEmpty()
                        ? 0
                        : positiveTrades.stream()
                        .filter(t -> t.getProfitLoss() > 0)
                        .count() * 100.0
                        / positiveTrades.size();

        List<Integer> losingConfidence =
                trades.stream()
                        .filter(t -> t.getProfitLoss() < 0)
                        .map(this::getConfidence)
                        .filter(Objects::nonNull)
                        .toList();

        double avgLosingConfidence =
                losingConfidence.stream()
                        .mapToInt(Integer::intValue)
                        .average()
                        .orElse(0);

        List<PaperTrade> negativeTrades =
                trades.stream()
                        .filter(this::isNegativeSentiment)
                        .toList();

        double negativeWinRate =
                negativeTrades.isEmpty()
                        ? 0
                        : negativeTrades.stream()
                        .filter(t -> t.getProfitLoss() > 0)
                        .count() * 100.0
                        / negativeTrades.size();

        return TradeInsightsResponse.builder()
                .bestPerformingSymbol(best != null ? best.getKey() : "N/A")
                .bestPerformingProfit(
                        best != null ? best.getValue() : 0.0)

                .worstPerformingSymbol(
                        worst != null ? worst.getKey() : "N/A")
                .worstPerformingProfit(
                        worst != null ? worst.getValue() : 0.0)

                .averageWinningConfidence(
                        avgWinningConfidence)

                .averageLosingConfidence(avgLosingConfidence)

                .positiveSentimentWinRate(positiveWinRate)

                .negativeSentimentWinRate(negativeWinRate)

                .totalClosedTrades(
                        trades.size())

                .build();
    }

    private Integer getConfidence(
            PaperTrade trade) {

        return opportunityRepository
                .findBySignalId(trade.getSignalId())
                .map(Opportunity::getConfidence)
                .orElse(null);
    }

    private boolean isNegativeSentiment(
            PaperTrade trade) {

        return opportunityRepository
                .findBySignalId(trade.getSignalId())
                .map(o ->
                        "NEGATIVE".equalsIgnoreCase(
                                o.getSentiment()))
                .orElse(false);
    }

    private boolean isPositiveSentiment(
            PaperTrade trade) {
        return opportunityRepository
                .findBySignalId(trade.getSignalId())
                .map(o -> "POSITIVE".equalsIgnoreCase(
                        o.getSentiment()))
                .orElse(false);
    }
}