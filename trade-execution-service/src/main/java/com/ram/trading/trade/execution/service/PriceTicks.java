package com.ram.trading.trade.execution.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Aligns protective prices to the exchange tick size. NSE rejects a trigger or
 * limit price that is not a multiple of the tick (0.05 for most equities; 0.05 is
 * also a valid multiple where the tick is 0.01). Signal Engine produces prices
 * rounded to 2 decimals, so without this a broker stop such as 1234.57 is rejected
 * and the position stays unprotected at the broker.
 *
 * Rounding is always towards the position's side of the market, never looser than
 * requested: a long position's stop rounds up and its target down; a short
 * position's stop rounds down and its target up.
 */
public final class PriceTicks {

    private PriceTicks() {
    }

    public static BigDecimal alignStop(BigDecimal price, String positionSide, BigDecimal tick) {
        return align(price, tick, isBuy(positionSide) ? RoundingMode.CEILING : RoundingMode.FLOOR);
    }

    public static BigDecimal alignTarget(BigDecimal price, String positionSide, BigDecimal tick) {
        return align(price, tick, isBuy(positionSide) ? RoundingMode.FLOOR : RoundingMode.CEILING);
    }

    private static BigDecimal align(BigDecimal price, BigDecimal tick, RoundingMode mode) {
        if (price == null || tick == null || tick.signum() <= 0) {
            return price;
        }
        // Drop floating-point noise first (99.35000000000001 must not ceil to 99.40).
        BigDecimal clean = price.setScale(6, RoundingMode.HALF_UP);
        return clean.divide(tick, 0, mode).multiply(tick);
    }

    private static boolean isBuy(String side) {
        return side != null && "BUY".equalsIgnoreCase(side.trim());
    }
}
