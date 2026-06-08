package com.ram.trading.backtesting.service;

import com.ram.trading.backtesting.dto.BacktestSummary;
import com.ram.trading.backtesting.dto.OptimizationRequest;
import com.ram.trading.backtesting.dto.OptimizationResponse;
import com.ram.trading.backtesting.dto.StrategyResult;
import com.ram.trading.backtesting.entity.TechnicalIndicator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
@RequiredArgsConstructor
public class StrategyOptimizerService {

    private final BacktestingService backtestService;

    public OptimizationResponse optimize(
            OptimizationRequest request) {

        List<Future<StrategyResult>> futures =
                new ArrayList<>();

        try (var executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {

            for (double buy = request.buyStart();
                 buy <= request.buyEnd();
                 buy += request.buyStep()) {

                for (double target = request.targetStart();
                     target <= request.targetEnd();
                     target += request.targetStep()) {

                    for (double stop = request.stopStart();
                         stop <= request.stopEnd();
                         stop += request.stopStep()) {

                        double finalBuy = buy;
                        double finalTarget = target;
                        double finalStop = stop;

                        futures.add(
                                executor.submit(() -> {

                                    BacktestSummary summary =
                                            backtestService
                                                    .runBacktestSummary(
                                                            request.symbol(),
                                                            finalBuy,
                                                            finalTarget,
                                                            finalStop
                                                    );

                                    return new StrategyResult(
                                            finalBuy,
                                            finalTarget,
                                            finalStop,
                                            summary.getTotalTrades(),
                                            summary.getWinRate(),
                                            summary.getNetProfit()
                                    );
                                })
                        );
                    }
                }
            }

            List<StrategyResult> results =
                    new ArrayList<>();

            for (Future<StrategyResult> future : futures) {
                results.add(future.get());
            }

            results.sort(
                    Comparator.comparing(
                                    StrategyResult::profit)
                            .reversed());

            return new OptimizationResponse(
                    results.size(),
                    results.getFirst(),
                    results.stream()
                            .limit(10)
                            .toList()
            );
        }
        catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

}