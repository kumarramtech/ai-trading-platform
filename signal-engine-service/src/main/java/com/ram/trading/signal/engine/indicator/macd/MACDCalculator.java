package com.ram.trading.signal.engine.indicator.macd;

import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.indicator.ema.EMACalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MACDCalculator {

    private final EMACalculator emaCalculator;

    public MACDResult calculate(List<Candle> candles) {

        List<BigDecimal> ema12 =
                emaCalculator.calculateSeries(candles, 12);

        List<BigDecimal> ema26 =
                emaCalculator.calculateSeries(candles, 26);

        int offset = ema12.size() - ema26.size();

        List<BigDecimal> macdSeries = new ArrayList<>();

        for (int i = 0; i < ema26.size(); i++) {

            macdSeries.add(
                    ema12.get(i + offset)
                            .subtract(ema26.get(i)));

        }

        List<BigDecimal> signalSeries = emaCalculator.calculateSeriesFromValues(macdSeries, 9);

        BigDecimal macd = macdSeries.get(macdSeries.size() - 1);

        BigDecimal signal = signalSeries.get(signalSeries.size() - 1);

        BigDecimal histogram = macd.subtract(signal);

        IndicatorSignal trend;

        if (macd.compareTo(signal) > 0) {

            trend = IndicatorSignal.BULLISH;

        } else if (macd.compareTo(signal) < 0) {

            trend = IndicatorSignal.BEARISH;

        } else {

            trend = IndicatorSignal.NEUTRAL;

        }

        return MACDResult.builder()
                .macd(macd.setScale(2, RoundingMode.HALF_UP))
                .signal(signal.setScale(2, RoundingMode.HALF_UP))
                .histogram(histogram.setScale(2, RoundingMode.HALF_UP))
                .trend(trend)
                .build();

    }

}