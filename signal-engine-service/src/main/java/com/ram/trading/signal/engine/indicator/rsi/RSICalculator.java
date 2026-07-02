package com.ram.trading.signal.engine.indicator.rsi;

import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.indicator.util.IndicatorMathUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
@Slf4j
public class RSICalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 10;

    public BigDecimal calculate(List<Candle> candles, int period) {

        BigDecimal totalGain = ZERO;
        BigDecimal totalLoss = ZERO;

        // Initial Average Gain/Loss
        for (int i = 1; i <= period; i++) {

            BigDecimal change = priceChange(candles, i);


            if (change.compareTo(ZERO) > 0) {
                totalGain = totalGain.add(change);
            } else {
                totalLoss = totalLoss.add(change.abs());
            }
        }

        BigDecimal averageGain = average(totalGain, period);
        BigDecimal averageLoss = average(totalLoss, period);

        // Wilder Smoothing
        for (int i = period + 1; i < candles.size(); i++) {

            BigDecimal change = priceChange(candles, i);

            BigDecimal currentGain =
                    change.compareTo(ZERO) > 0
                            ? change
                            : ZERO;

            BigDecimal currentLoss =
                    change.compareTo(ZERO) < 0
                            ? change.abs()
                            : ZERO;

            averageGain = wilderAverage(
                    averageGain,
                    currentGain,
                    period);

            averageLoss = wilderAverage(
                    averageLoss,
                    currentLoss,
                    period);
        }

        if (averageGain.compareTo(ZERO) == 0) {
            return ZERO;
        }

        if (averageLoss.compareTo(ZERO) == 0) {
            return HUNDRED;
        }

        BigDecimal rs =
                IndicatorMathUtil.safeDivide(
                        averageGain,
                        averageLoss);

        BigDecimal rsi =
                HUNDRED.subtract(
                        HUNDRED.divide(
                                rs.add(ONE),
                                SCALE,
                                RoundingMode.HALF_UP));

        log.info("RS : {}", rs);
        log.info("RSI : {}", rsi);

        return IndicatorMathUtil.round(rsi);
    }

    private BigDecimal priceChange(
            List<Candle> candles,
            int index) {

        return candles.get(index)
                .getClose()
                .subtract(
                        candles.get(index - 1)
                                .getClose());
    }

    private BigDecimal average(
            BigDecimal total,
            int period) {

        return total.divide(
                BigDecimal.valueOf(period),
                SCALE,
                RoundingMode.HALF_UP);
    }

    private BigDecimal wilderAverage(
            BigDecimal previousAverage,
            BigDecimal currentValue,
            int period) {

        return previousAverage
                .multiply(BigDecimal.valueOf(period - 1))
                .add(currentValue)
                .divide(
                        BigDecimal.valueOf(period),
                        SCALE,
                        RoundingMode.HALF_UP);
    }

}