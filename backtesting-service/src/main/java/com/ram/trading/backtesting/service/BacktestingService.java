package com.ram.trading.backtesting.service;

import com.ram.trading.backtesting.dto.BacktestResult;
import com.ram.trading.backtesting.dto.BacktestSummary;
import com.ram.trading.backtesting.entity.HistoricalPrice;
import com.ram.trading.backtesting.entity.TechnicalIndicator;
import com.ram.trading.backtesting.repo.HistoricalPriceRepository;
import com.ram.trading.backtesting.repo.TechnicalIndicatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestingService {

    private final HistoricalPriceRepository historicalPriceRepository;

    private final TechnicalIndicatorRepository technicalIndicatorRepository;

    private static final double BUY_DROP_PERCENT = 3.0;
    private static final double SELL_PROFIT_PERCENT = 5.0;

    private static final double STOP_LOSS_PERCENT = 2.0;

    public BacktestResult runBacktest(String symbol) throws Exception {

        List<HistoricalPrice> prices =
                historicalPriceRepository.findBySymbolOrderByTradeDateAsc(symbol);

        Double buyPrice = null;
        Double sellPrice = null;

        for (HistoricalPrice price : prices) {

            if (buyPrice == null &&
                    price.getPrice() < 1000) {

                buyPrice = price.getPrice();
            }

            if (buyPrice != null &&
                    price.getPrice() > 1400) {

                sellPrice = price.getPrice();
                break;
            }
        }

        double profit = 0;

        if (buyPrice != null && sellPrice != null) {
            profit = sellPrice - buyPrice;
        }

        return new BacktestResult(
                symbol,
                buyPrice,
                sellPrice,
                profit
        );
    }

    public BacktestSummary runBacktestSummary(String symbol) {

        List<HistoricalPrice> prices =
                historicalPriceRepository.findBySymbolOrderByTradeDateAsc(symbol);

        Double buyPrice = null;
        Double previousPrice = null;

        int totalTrades = 0;
        int winningTrades = 0;
        int losingTrades = 0;

        double netProfit = 0.0;

        for (HistoricalPrice historicalPrice : prices) {

            Double currentPrice = historicalPrice.getPrice();

            if (previousPrice == null) {
                previousPrice = currentPrice;
                continue;
            }

            // BUY Condition
            if (buyPrice == null) {

                double dropPercentage =
                        ((previousPrice - currentPrice)
                                / previousPrice) * 100;

                if (dropPercentage >= BUY_DROP_PERCENT) {

                    buyPrice = currentPrice;

                    System.out.println(
                            "BUY @ " + buyPrice);
                }
            }

            // SELL Condition
            else {

                double profitPercentage =
                        ((currentPrice - buyPrice)
                                / buyPrice) * 100;

                double lossPercentage =
                        ((buyPrice - currentPrice)
                                / buyPrice) * 100;

                if (profitPercentage >= SELL_PROFIT_PERCENT) {

                    double profit = currentPrice - buyPrice;

                    netProfit += profit;
                    totalTrades++;
                    winningTrades++;

                    buyPrice = null;
                }
                else if (lossPercentage >= STOP_LOSS_PERCENT) {

                    double loss = currentPrice - buyPrice;

                    netProfit += loss;
                    totalTrades++;
                    losingTrades++;

                    buyPrice = null;
                }
            }

            previousPrice = currentPrice;
        }

        if (buyPrice != null) {

            double finalPrice =
                    prices.getLast().getPrice();

            double profit =
                    finalPrice - buyPrice;

            netProfit += profit;

            totalTrades++;

            if (profit > 0) {
                winningTrades++;
            } else {
                losingTrades++;
            }
        }

        double winRate = 0.0;

        if (totalTrades > 0) {
            winRate =
                    (winningTrades * 100.0)
                            / totalTrades;
        }

        return new BacktestSummary(
                symbol,
                totalTrades,
                winningTrades,
                losingTrades,
                winRate,
                netProfit
        );
    }

    public BacktestSummary runBacktestSummary(
            String symbol,
            Double buy,
            Double target,
            Double stop) {

        buy = buy == null ? 3.0 : buy;
        target = target == null ? 5.0 : target;
        stop = stop == null ? 2.0 : stop;

        List<HistoricalPrice> prices =
                historicalPriceRepository.findBySymbolOrderByTradeDateAsc(symbol);

        Double buyPrice = null;
        Double previousPrice = null;

        int totalTrades = 0;
        int winningTrades = 0;
        int losingTrades = 0;

        double netProfit = 0.0;

        for (HistoricalPrice historicalPrice : prices) {

            Double currentPrice = historicalPrice.getPrice();

            if (previousPrice == null) {
                previousPrice = currentPrice;
                continue;
            }

            if (buyPrice == null) {

                double dropPercentage =
                        ((previousPrice - currentPrice)
                                / previousPrice) * 100;

                if (dropPercentage >= buy) {

                    buyPrice = currentPrice;

                    log.debug("BUY @ {}", buyPrice);
                }
            }
            else {

                double profitPercentage =
                        ((currentPrice - buyPrice)
                                / buyPrice) * 100;

                double lossPercentage =
                        ((buyPrice - currentPrice)
                                / buyPrice) * 100;

                if (profitPercentage >= target) {

                    double profit =
                            currentPrice - buyPrice;

                    netProfit += profit;

                    totalTrades++;
                    winningTrades++;

                    buyPrice = null;
                }
                else if (lossPercentage >= stop) {

                    double loss =
                            currentPrice - buyPrice;

                    netProfit += loss;

                    totalTrades++;
                    losingTrades++;

                    buyPrice = null;
                }
            }

            previousPrice = currentPrice;
        }

        double winRate = 0.0;

        if (totalTrades > 0) {
            winRate =
                    (winningTrades * 100.0)
                            / totalTrades;
        }

        return new BacktestSummary(
                symbol,
                totalTrades,
                winningTrades,
                losingTrades,
                winRate,
                netProfit
        );
    }

    public TechnicalIndicator findTopBySymbolOrderByTradeDateDesc(String symbol) {
        return technicalIndicatorRepository
                .findTopBySymbolOrderByTradeDateDesc(
                        symbol)
                .orElseThrow(
                        () -> new RuntimeException(
                                "Indicator not found"));

    }
}