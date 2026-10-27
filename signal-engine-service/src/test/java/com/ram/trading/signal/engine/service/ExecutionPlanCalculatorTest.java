package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionPlanCalculatorTest {

    private final OpeningRangeService openingRangeService = mock(OpeningRangeService.class);
    private final ExecutionPlanCalculator calculator = new ExecutionPlanCalculator(openingRangeService);

    private OpeningRange range(double high, double low) {
        OpeningRange r = new OpeningRange();
        r.setHigh(high);
        r.setLow(low);
        return r;
    }

    private TradingSignal signal(String direction, double entry) {
        TradingSignal s = new TradingSignal();
        s.setSymbol("ABC");
        s.setSignal(direction);
        s.setEntryPrice(entry);
        return s;
    }

    @Test
    void buyUsesOpeningRangeLowAndTwoToOneTarget() {
        // entry 100, OR low 99.40 -> risk ~0.65 (inside 0.4%..1.0%)
        when(openingRangeService.get("ABC")).thenReturn(range(101.0, 99.40));
        TradingSignal s = signal("BUY", 100.0);

        calculator.apply(s, null);

        assertTrue(s.getStopLoss() < 100.0 && 100.0 < s.getTargetPrice());
        double risk = 100.0 - s.getStopLoss();
        assertEquals(2.0 * risk, s.getTargetPrice() - 100.0, 0.02);
        assertTrue(risk >= 0.40 && risk <= 1.00);
    }

    @Test
    void sellUsesOpeningRangeHigh() {
        when(openingRangeService.get("ABC")).thenReturn(range(100.6, 98.0));
        TradingSignal s = signal("SELL", 100.0);

        calculator.apply(s, null);

        assertTrue(s.getTargetPrice() < 100.0 && 100.0 < s.getStopLoss());
        double risk = s.getStopLoss() - 100.0;
        assertEquals(2.0 * risk, 100.0 - s.getTargetPrice(), 0.02);
    }

    @Test
    void wideRangeIsSkippedInsteadOfClamped() {
        when(openingRangeService.get("ABC")).thenReturn(range(110.0, 90.0));
        TradingSignal s = signal("BUY", 100.0);

        assertFalse(calculator.apply(s, null));
        assertNull(s.getStopLoss());
    }

    @Test
    void wideRangeIsClampedToMaxRiskWhenRejectionIsOff() {
        org.springframework.test.util.ReflectionTestUtils.setField(calculator, "rejectClampedOrStop", false);
        when(openingRangeService.get("ABC")).thenReturn(range(110.0, 90.0));
        TradingSignal s = signal("BUY", 100.0);

        assertTrue(calculator.apply(s, null));

        assertEquals(99.0, s.getStopLoss(), 0.01);
        assertEquals(102.0, s.getTargetPrice(), 0.01);
    }

    @Test
    void missingRangeUsesFallbackPercent() {
        when(openingRangeService.get("ABC")).thenReturn(null);
        TradingSignal s = signal("SELL", 200.0);

        calculator.apply(s, null);

        assertEquals(201.2, s.getStopLoss(), 0.01);
        assertEquals(197.6, s.getTargetPrice(), 0.01);
    }

    private static SignalGenerationRequest judas(double sweepExtreme) {
        return SignalGenerationRequest.builder()
                .symbol("ABC")
                .setupStrategy("JUDAS")
                .setupInvalidationLevel(sweepExtreme)
                .build();
    }

    @Test
    void judasSellStopSitsBeyondSweepHighNotAtIt() {
        // COCHINSHIP 2026-10-26: entry 1135.6, OR high 1138.1, sweep high 1140.0.
        // Its 0.49% stop is now skipped by min-stop-pct; this test is about stop placement.
        org.springframework.test.util.ReflectionTestUtils.setField(calculator, "minStopPct", 0.0);
        when(openingRangeService.get("ABC")).thenReturn(range(1138.1, 1120.0));
        TradingSignal s = signal("SELL", 1135.6);

        assertTrue(calculator.apply(s, judas(1140.0)));

        // 1140.0 + 0.10% buffer (1.1356) = 1141.1356 -> short stop rounds toward entry: 1141.10
        // (old plan: 1140.10, a single tick above the sweep high)
        assertEquals(1141.10, s.getStopLoss(), 0.001);
        assertTrue(s.getTargetPrice() < 1135.6);
    }

    @Test
    void lowPricedJudasStopStaysAtLeastOneTickBeyondSweep() {
        TradingSignal s = signal("SELL", 29.80);

        assertTrue(calculator.apply(s, judas(30.0)));

        // 0.10% of 29.80 is 0.03 (< tick); the buffer is raised to one tick.
        assertEquals(30.05, s.getStopLoss(), 0.001);
    }

    @Test
    void judasBuyStopSitsBelowSweepLow() {
        TradingSignal s = signal("BUY", 100.0);

        assertTrue(calculator.apply(s, judas(99.5)));

        assertTrue(s.getStopLoss() < 99.5);
        assertEquals(99.4, s.getStopLoss(), 0.001);
    }

    @Test
    void judasSweepTooFarAwayIsRejectedInsteadOfClamped() {
        TradingSignal s = signal("SELL", 100.0);

        assertFalse(calculator.apply(s, judas(101.5)));
        assertNull(s.getStopLoss());
    }

    @Test
    void judasStopTighterThanMinimumIsSkippedNotWidened() {
        // Sweep 100.30 + 0.10 buffer -> 0.40% stop, under the 0.60% minimum.
        TradingSignal s = signal("SELL", 100.0);

        assertFalse(calculator.apply(s, judas(100.30)));
        assertNull(s.getStopLoss());
    }

    @Test
    void judasStopAtTheMinimumIsTraded() {
        // Sweep 100.50 + 0.10 buffer -> exactly 0.60%.
        TradingSignal s = signal("SELL", 100.0);

        assertTrue(calculator.apply(s, judas(100.50)));
        assertEquals(100.60, s.getStopLoss(), 0.001);
    }

    @Test
    void openingRangeStopTighterThanMinimumIsSkipped() {
        // OR high 100.30 + 0.05 buffer -> 0.35% stop.
        when(openingRangeService.get("ABC")).thenReturn(range(100.30, 98.0));
        TradingSignal s = signal("SELL", 100.0);

        assertFalse(calculator.apply(s, null));
        assertNull(s.getStopLoss());
    }

    @Test
    void fallbackStopIsNotAffectedByTheMinimum() {
        when(openingRangeService.get("ABC")).thenReturn(null);
        TradingSignal s = signal("BUY", 200.0);

        assertTrue(calculator.apply(s, null));
        assertEquals(198.8, s.getStopLoss(), 0.01);
    }

    @Test
    void holdAndInvalidEntryAreUntouched() {
        TradingSignal hold = signal("HOLD", 100.0);
        calculator.apply(hold, null);
        assertNull(hold.getStopLoss());
        assertNull(hold.getTargetPrice());

        TradingSignal bad = signal("BUY", 0.0);
        calculator.apply(bad, null);
        assertNull(bad.getStopLoss());
    }
}
