package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.dto.market.ExitReason;
import com.ram.trading.signal.engine.dto.market.OpenPosition;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.exit.ExitType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StopLossStrategyTest {

    private final StopLossStrategy strategy = new StopLossStrategy();

    @Test
    void buyPositionExitsWhenPriceTouchesOrFallsBelowStopLoss() {
        OpenPosition position = OpenPosition.builder()
                .symbol("TCS").signal("BUY").stopLoss(99.0).build();

        ExitDecision decision = strategy.evaluate(position, Tick.builder()
                .symbol("TCS").lastTradedPrice(99.0).build());

        assertTrue(decision.isExit());
        assertEquals(ExitReason.STOP_LOSS, decision.getReason());
        assertEquals(ExitType.STOP_LOSS, decision.getExitType());
        assertEquals(99.0, decision.getExitPrice(), 0.0001);
    }

    @Test
    void buyPositionDoesNotExitAboveStopLoss() {
        OpenPosition position = OpenPosition.builder()
                .symbol("TCS").signal("BUY").stopLoss(99.0).build();

        ExitDecision decision = strategy.evaluate(position, Tick.builder()
                .symbol("TCS").lastTradedPrice(99.01).build());

        assertFalse(decision.isExit());
    }

    @Test
    void sellPositionExitsWhenPriceTouchesOrRisesAboveStopLoss() {
        OpenPosition position = OpenPosition.builder()
                .symbol("TCS").signal("SELL").stopLoss(101.0).build();

        ExitDecision decision = strategy.evaluate(position, Tick.builder()
                .symbol("TCS").lastTradedPrice(101.0).build());

        assertTrue(decision.isExit());
        assertEquals(ExitReason.STOP_LOSS, decision.getReason());
        assertEquals(101.0, decision.getExitPrice(), 0.0001);
    }

    @Test
    void sellPositionDoesNotExitBelowStopLoss() {
        OpenPosition position = OpenPosition.builder()
                .symbol("TCS").signal("SELL").stopLoss(101.0).build();

        ExitDecision decision = strategy.evaluate(position, Tick.builder()
                .symbol("TCS").lastTradedPrice(100.99).build());

        assertFalse(decision.isExit());
    }
}
