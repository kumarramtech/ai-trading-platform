package com.ram.trading.backtesting.service;

import com.ram.trading.backtesting.entity.HistoricalPrice;
import com.ram.trading.backtesting.entity.TechnicalIndicator;
import com.ram.trading.backtesting.repo.HistoricalPriceRepository;
import com.ram.trading.backtesting.repo.TechnicalIndicatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IndicatorService {

    private final HistoricalPriceRepository repository;
    private final TechnicalIndicatorRepository indicatorRepository;

    public void generateRsi(String symbol) {

        List<TechnicalIndicator> indicators =
                new ArrayList<>();

        List<HistoricalPrice> prices =
                repository.findBySymbolOrderByTradeDateAsc(symbol);

        List<Double> ema12List =
                calculateEma(prices, 12);

        List<Double> ema20List =
                calculateEma(prices, 20);

        List<Double> ema26List =
                calculateEma(prices, 26);

        List<Double> ema50List =
                calculateEma(prices, 50);


        if (prices.size() < 15) {
            return;
        }

        for (int i = 0; i < prices.size(); i++) {

            Double sma20 =
                    calculateSma(prices, i, 20);

            Double sma50 =
                    calculateSma(prices, i, 50);

            double rsi =
                    calculateRsi(prices, i);

            double gains = 0;
            double losses = 0;

            for (int j = i - 13; j <= i; j++) {

                double current =
                        prices.get(j).getPrice();

                double previous =
                        prices.get(j - 1).getPrice();

                double change =
                        current - previous;

                if (change > 0) {
                    gains += change;
                } else {
                    losses += Math.abs(change);
                }
            }

            double avgGain = gains / 14;
            double avgLoss = losses / 14;

            //double rsi;

            if (avgLoss == 0) {
                rsi = 100;
            } else {

                double rs =
                        avgGain / avgLoss;

                rsi =
                        100 -
                        (100 / (1 + rs));
            }



            Double ema20 =
                    ema20List.get(i);

            Double ema50 =
                    ema50List.get(i);

            Double macd =
                    calculateMacd(
                            ema12List.get(i),
                            ema26List.get(i));


            HistoricalPrice price =
                    prices.get(i);

            TechnicalIndicator indicator =
                    TechnicalIndicator.builder()
                            .symbol(symbol)
                            .tradeDate(price.getTradeDate())
                            .closePrice(price.getPrice())

                            .rsi14(rsi)

                            .sma20(sma20)
                            .sma50(sma50)

                            .ema20(ema20)
                            .ema50(ema50)

                            .macd(macd)

                            .build();
            indicators.add(indicator);
        }
        indicatorRepository.saveAll(indicators);
    }

    public List<TechnicalIndicator> findBySymbolOrderByTradeDateAsc(String symbol) {
        return indicatorRepository.findBySymbolOrderByTradeDateAsc(symbol);
    }

    private Double calculateSma(
            List<HistoricalPrice> prices,
            int currentIndex,
            int period) {

        if (currentIndex < period - 1) {
            return null;
        }

        double sum = 0;

        for (int i = currentIndex - period + 1;
             i <= currentIndex;
             i++) {

            sum += prices.get(i).getPrice();
        }

        return sum / period;
    }

    private List<Double> calculateEma(
            List<HistoricalPrice> prices,
            int period) {

        List<Double> emaValues = new ArrayList<>();

        double multiplier =
                2.0 / (period + 1);

        double sma = 0.0;

        for (int i = 0; i < period; i++) {
            sma += prices.get(i).getPrice();
        }

        sma /= period;

        for (int i = 0; i < period - 1; i++) {
            emaValues.add(null);
        }

        emaValues.add(sma);

        double previousEma = sma;

        for (int i = period; i < prices.size(); i++) {

            double closePrice =
                    prices.get(i).getPrice();

            double ema =
                    (closePrice * multiplier)
                            + (previousEma * (1 - multiplier));

            emaValues.add(ema);

            previousEma = ema;
        }

        return emaValues;
    }

    private Double calculateMacd(
            Double ema12,
            Double ema26) {

        if (ema12 == null || ema26 == null) {
            return null;
        }

        return ema12 - ema26;
    }

    private Double calculateRsi(
            List<HistoricalPrice> prices,
            int currentIndex) {

        if (currentIndex < 14) {
            return null;
        }

        double gains = 0;
        double losses = 0;

        for (int j = currentIndex - 13;
             j <= currentIndex;
             j++) {

            double current =
                    prices.get(j).getPrice();

            double previous =
                    prices.get(j - 1).getPrice();

            double change =
                    current - previous;

            if (change > 0) {
                gains += change;
            } else {
                losses += Math.abs(change);
            }
        }

        double avgGain = gains / 14;
        double avgLoss = losses / 14;

        if (avgLoss == 0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;

        return 100 - (100 / (1 + rs));
    }
}