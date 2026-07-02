package com.ram.trading.signal.engine.indicator.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class IndicatorMathUtil {

    private IndicatorMathUtil() {
    }
    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal safeDivide(
            BigDecimal numerator,
            BigDecimal denominator) {

        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return numerator.divide(
                denominator,
                10,
                RoundingMode.HALF_UP);
    }

}