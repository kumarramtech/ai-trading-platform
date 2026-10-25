package com.ram.trading.trade.execution;

import com.ram.trading.trade.execution.service.PriceTicks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PriceTicksTest {

    private static final BigDecimal TICK = new BigDecimal("0.05");

    @Test
    void longStopRoundsUpAndTargetRoundsDown() {
        assertMoney("1234.60", PriceTicks.alignStop(new BigDecimal("1234.57"), "BUY", TICK));
        assertMoney("1259.10", PriceTicks.alignTarget(new BigDecimal("1259.13"), "BUY", TICK));
    }

    @Test
    void shortStopRoundsDownAndTargetRoundsUp() {
        assertMoney("1234.55", PriceTicks.alignStop(new BigDecimal("1234.57"), "SELL", TICK));
        assertMoney("1209.15", PriceTicks.alignTarget(new BigDecimal("1209.13"), "SELL", TICK));
    }

    @Test
    void alignedPricesAndNullsAreUnchanged() {
        assertMoney("100.05", PriceTicks.alignStop(new BigDecimal("100.05"), "BUY", TICK));
        assertNull(PriceTicks.alignTarget(null, "BUY", TICK));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "was " + actual);
    }
}
