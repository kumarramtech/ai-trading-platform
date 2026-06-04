package com.ram.trading.backtesting.service;

import com.ram.trading.backtesting.dto.BacktestResult;
import com.ram.trading.backtesting.dto.BacktestSummary;
import com.ram.trading.backtesting.entity.HistoricalPrice;
import com.ram.trading.backtesting.repo.HistoricalPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BacktestingService {

    private final HistoricalPriceRepository repository;

    public BacktestResult runBacktest(String symbol) {

        List<HistoricalPrice> prices =
                repository.findBySymbolOrderByTradeDateAsc(symbol);

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
                repository
                        .findBySymbolOrderByTradeDateAsc(symbol);

        Double buyPrice = null;

        int totalTrades = 0;
        int winningTrades = 0;
        int losingTrades = 0;

        double netProfit = 0.0;

        for (HistoricalPrice historicalPrice : prices) {

            Double currentPrice = historicalPrice.getPrice();

            // BUY Condition
            if (buyPrice == null && currentPrice < 1000) {
                buyPrice = currentPrice;
            }

            // SELL Condition
            else if (buyPrice != null && currentPrice > 1400) {

                double profit = currentPrice - buyPrice;

                netProfit += profit;

                totalTrades++;

                if (profit > 0) {
                    winningTrades++;
                } else {
                    losingTrades++;
                }

                // Ready for next trade
                buyPrice = null;
            }
        }

        double winRate = 0.0;

        if (totalTrades > 0) {
            winRate =
                    (winningTrades * 100.0) / totalTrades;
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
}