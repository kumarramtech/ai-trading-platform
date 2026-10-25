package com.ram.trading.signal.engine.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Aligns stop and target prices to the exchange tick size. NSE rejects broker
 * orders whose trigger price is not a tick multiple, and Trade Execution aligns
 * every stop/target it receives with the same rule, so prices proposed here must
 * already be aligned for their confirmations to match.
 *
 * Rounding is towards the position's side of the market: a long position's stop
 * rounds up and its target down; a short position's stop rounds down and its
 * target up. Keep in sync with Trade Execution's PriceTicks.
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

    public static double alignStop(double price, String positionSide, double tick) {
        return alignStop(BigDecimal.valueOf(price), positionSide, BigDecimal.valueOf(tick)).doubleValue();
    }

    public static double alignTarget(double price, String positionSide, double tick) {
        return alignTarget(BigDecimal.valueOf(price), positionSide, BigDecimal.valueOf(tick)).doubleValue();
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
