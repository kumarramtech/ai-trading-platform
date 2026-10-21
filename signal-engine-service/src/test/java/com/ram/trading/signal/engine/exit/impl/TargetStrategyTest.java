package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.dto.market.ExitReason;
import com.ram.trading.signal.engine.dto.market.OpenPosition;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.exit.ExitType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetStrategyTest {

    private final TargetStrategy strategy = new TargetStrategy();

    @Test
    void buyPositionExitsWhenPriceTouchesOrExceedsTarget() {
        OpenPosition position = OpenPosition.builder()
                .symbol("TCS").signal("BUY").targetPrice(102.0).build();

        ExitDecision decision = strategy.evaluate(position, Tick.builder()
                .symbol("TCS").lastTradedPrice(102.0).build());

        assertTrue(decision.isExit());
        assertEquals(ExitReason.TARGET, decision.getReason());
        assertEquals(ExitType.TARGET, decision.getExitType());
        assertEquals(102.0, decision.getExitPrice(), 0.0001);
    }

    @Test
    void buyPositionDoesNotExitBelowTarget() {
        OpenPosition position = OpenPosition.builder()
                .symbol("TCS").signal("BUY").targetPrice(102.0).build();

        ExitDecision decision = strategy.evaluate(position, Tick.builder()
                .symbol("TCS").lastTradedPrice(101.99).build());

        assertFalse(decision.isExit());
    }

    @Test
    void sellPositionExitsWhenPriceTouchesOrFallsBelowTarget() {
        OpenPosition position = OpenPosition.builder()
                .symbol("TCS").signal("SELL").targetPrice(98.0).build();

        ExitDecision decision = strategy.evaluate(position, Tick.builder()
                .symbol("TCS").lastTradedPrice(98.0).build());

        assertTrue(decision.isExit());
        assertEquals(ExitReason.TARGET, decision.getReason());
        assertEquals(98.0, decision.getExitPrice(), 0.0001);
    }

    @Test
    void sellPositionDoesNotExitAboveTarget() {
        OpenPosition position = OpenPosition.builder()
                .symbol("TCS").signal("SELL").targetPrice(98.0).build();

        ExitDecision decision = strategy.evaluate(position, Tick.builder()
                .symbol("TCS").lastTradedPrice(98.01).build());

        assertFalse(decision.isExit());
    }
}
