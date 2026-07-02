package com.ram.trading.signal.engine.indicator.util;


import java.util.List;
import com.ram.trading.signal.engine.dto.indicator.Candle;
public final class IndicatorValidationUtil {

    private IndicatorValidationUtil() {
    }

    public static void validate(List<Candle> candles, int period) {

        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("Candles cannot be null or empty.");
        }

        if (period <= 0) {
            throw new IllegalArgumentException("Period must be greater than zero.");
        }

        if (candles.size() < period) {
            throw new IllegalArgumentException(
                    "Not enough candles. Required: "
                            + period
                            + ", Available: "
                            + candles.size());
        }
    }
}