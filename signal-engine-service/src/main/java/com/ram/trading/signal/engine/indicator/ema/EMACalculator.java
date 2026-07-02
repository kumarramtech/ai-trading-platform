package com.ram.trading.signal.engine.indicator.ema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import com.ram.trading.signal.engine.dto.indicator.Candle;
@Slf4j
@Component
public class EMACalculator {

    private static final int SCALE = 10;

    public BigDecimal calculate(List<Candle> candles, int period) {

        List<BigDecimal> series = calculateSeries(candles, period);

        return series.get(series.size() - 1)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /*private BigDecimal calculateInitialSMA(List<Candle> candles, int period) {

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(candles.get(i).getClose());
        }
        return sum.divide(BigDecimal.valueOf(period),SCALE,RoundingMode.HALF_UP);
    }*/


    public List<BigDecimal> calculateSeries(List<Candle> candles, int period) {

        List<BigDecimal> closes = candles.stream()
                .map(Candle::getClose)
                .toList();

        return calculateEMA(closes, period);
    }

    public List<BigDecimal> calculateSeriesFromValues(
            List<BigDecimal> values,
            int period) {

        return calculateEMA(values, period);

    }



    private List<BigDecimal> calculateEMA(List<BigDecimal> values,
                                          int period) {

        List<BigDecimal> emaSeries = new ArrayList<>();

        BigDecimal multiplier = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1),
                        SCALE,
                        RoundingMode.HALF_UP);

        BigDecimal sma = calculateInitialSMA(values, period);

        BigDecimal ema = sma;

        emaSeries.add(ema);

        for (int i = period; i < values.size(); i++) {

            BigDecimal current = values.get(i);

            ema = current.subtract(ema)
                    .multiply(multiplier)
                    .add(ema);

            emaSeries.add(ema);
        }

        return emaSeries;
    }

    private BigDecimal calculateInitialSMA(List<BigDecimal> values,
                                           int period) {

        BigDecimal sum = BigDecimal.ZERO;

        for (int i = 0; i < period; i++) {

            sum = sum.add(values.get(i));

        }

        return sum.divide(
                BigDecimal.valueOf(period),
                SCALE,
                RoundingMode.HALF_UP);
    }
}