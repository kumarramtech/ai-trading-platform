package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.StrategyReplayService.Result;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayExperimentsTest {

    private final IntradayChargesCalculator charges = new IntradayChargesCalculator();

    /** BUY at 100, stop 99 (1R = Rs 1/share); path: 100.5, 101.2, 100.1, 98.5. */
    private static Result buy() {
        return new Result(LocalDate.of(2026, 10, 1), LocalTime.of(10, 0), "ABC", "ORB", "BUY",
                "BULLISH", "ALIGNED", "LIVE_RULES", "STOP", LocalTime.of(10, 4),
                100.0, 99.0, 102.0, 99.0, 250, -250, 50, -1.2, 0, 1, 0.1, 0,
                new float[]{100.6f, 101.3f, 100.6f, 100.2f},
                new float[]{100.2f, 100.4f, 100.0f, 98.5f},
                new float[]{100.5f, 101.2f, 100.1f, 98.6f});
    }

    private static ReplayExperiments.Rule rule(String name) {
        return ReplayExperiments.RULES.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void oneRTargetIsHitWhereTheTwoRTargetIsStoppedOut() {
        ReplayExperiments.Outcome oneR = ReplayExperiments.simulate(buy(), rule("1R target"), 250, charges, 0, 1e9);
        ReplayExperiments.Outcome twoR = ReplayExperiments.simulate(buy(), rule("2R target (current)"), 250, charges, 0, 1e9);

        assertEquals(250.0, oneR.gross(), 1e-6);     // 250 shares x Rs 1
        assertEquals(-250.0, twoR.gross(), 1e-6);    // stopped at 99
        assertTrue(oneR.charges() > 0);
    }

    @Test
    void breakevenStopAfterOneRTurnsTheLossIntoAScratch() {
        ReplayExperiments.Outcome be = ReplayExperiments.simulate(buy(), rule("2R target, breakeven at 1R"), 250, charges, 0, 1e9);

        assertEquals(0.0, be.gross(), 1e-6);         // stopped at entry 100 on the 98.5 candle
    }

    @Test
    void largerRiskPaysASmallerShareOfRInCharges() {
        ReplayExperiments.Outcome small = ReplayExperiments.simulate(buy(), rule("1R target"), 250, charges, 0, 1e9);
        ReplayExperiments.Outcome large = ReplayExperiments.simulate(buy(), rule("1R target"), 1000, charges, 0, 1e9);

        assertTrue(large.charges() / 1000 < small.charges() / 250);
    }
}
