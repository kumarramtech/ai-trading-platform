package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.IntradayFixStudy.Stats;
import com.ram.trading.signal.engine.service.replay.IntradayFixStudy.Trade;
import com.ram.trading.signal.engine.service.replay.IntradayFixStudy.Variant;
import com.ram.trading.signal.engine.service.replay.StrategyReplayService.Result;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntradayFixStudyTest {

    private final IntradayChargesCalculator charges = new IntradayChargesCalculator();

    /** BUY at 300 at the given time, stop 298.5 (0.5%), target 303; path dips to 297.6 then ends at 306. */
    private static Result buy(LocalTime at) {
        return new Result(LocalDate.of(2026, 3, 2), at, "ABC", "ORB", "BUY",
                "BULLISH", "ALIGNED", "LIVE_RULES", "STOP", at.plusMinutes(3),
                300.0, 298.5, 303.0, 298.5, 166, -250, 40, -1.1, 0, 1, 0.1, 0,
                new float[]{300.8f, 299.5f, 302.0f, 306.5f},
                new float[]{299.9f, 297.6f, 299.0f, 302.5f},
                new float[]{300.5f, 298.0f, 301.5f, 306.0f});
    }

    private static Variant v(int i) {
        return IntradayFixStudy.VARIANTS.get(i);
    }

    @Test
    void variantsAreThePreRegisteredOnes() {
        assertEquals("BASE (live rules)", v(0).name());
        assertEquals(LocalTime.of(10, 30), v(1).cutoff());
        assertEquals(LocalTime.of(10, 0), v(2).cutoff());
        assertTrue(v(1).decides() && v(2).decides());
        assertEquals(2, IntradayFixStudy.VARIANTS.stream().filter(Variant::decides).count());
    }

    @Test
    void baseStopsOutAndTheWideStopHoldsToTheClose() {
        double[] base = IntradayFixStudy.simulate(buy(LocalTime.of(9, 40)), v(0), 1e9, 250, 0.03, 500000, charges);
        assertNotNull(base);
        assertTrue(base[0] < -1, "tight stop hit at 297.6: " + base[0]);
        // Wide stop only: 1.2% = 3.6 -> stop 296.4, not hit; target 303 still used -> exits at 303.
        double[] wide = IntradayFixStudy.simulate(buy(LocalTime.of(9, 40)), v(6), 1e9, 250, 0.03, 500000, charges);
        assertTrue(wide[1] > 0.7 && wide[1] < 0.9, "gross about +0.83R at the target: " + wide[1]);
        // All fixes: wide stop, no target -> out at the last close 306 = about +1.67R gross.
        double[] fix = IntradayFixStudy.simulate(buy(LocalTime.of(9, 40)), v(1), 1e9, 250, 0.03, 500000, charges);
        assertTrue(fix[1] > 1.5 && fix[1] < 1.7, "gross about +1.6R: " + fix[1]);
        assertTrue(fix[0] < fix[1], "charges come off");
    }

    @Test
    void filtersDropLateCheapAndThinTrades() {
        assertNull(IntradayFixStudy.simulate(buy(LocalTime.of(10, 30)), v(1), 1e9, 250, 0.03, 500000, charges));
        assertNotNull(IntradayFixStudy.simulate(buy(LocalTime.of(10, 29)), v(1), 1e9, 250, 0.03, 500000, charges));
        assertNull(IntradayFixStudy.simulate(buy(LocalTime.of(10, 0)), v(2), 1e9, 250, 0.03, 500000, charges));
        assertNull(IntradayFixStudy.simulate(buy(LocalTime.of(9, 40)), v(1), 20e7, 250, 0.03, 500000, charges),
                "Rs 20 crore turnover is below 25");
        assertNull(IntradayFixStudy.simulate(buy(LocalTime.of(9, 40)), v(1), Double.NaN, 250, 0.03, 500000, charges));
        assertNotNull(IntradayFixStudy.simulate(buy(LocalTime.of(11, 0)), v(0), Double.NaN, 250, 0.03, 500000, charges));
    }

    @Test
    void passRuleNeedsSizeMeanTAndBothHalves() {
        List<Trade> trades = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 120; i++) {
            trades.add(new Trade(d.plusDays(i / 2), "ORB", i % 3 == 0 ? -0.5 : 0.6));
        }
        Stats good = IntradayFixStudy.stats("x", trades, 0.5);
        assertEquals(120, good.trades());
        assertEquals(60, good.days());
        assertTrue(good.meanR() > 0.2 && good.t() > 2 && good.half1() > 0 && good.half2() > 0, good.toString());
        assertTrue(good.passes());
        assertFalse(new Stats("x", 99, 50, 0.3, 3, 0.3, 0.3, 50, 0.4).passes(), "too few trades");
        assertFalse(new Stats("x", 200, 90, 0.3, 3, 0.5, -0.01, 50, 0.4).passes(), "one half negative");
        assertFalse(new Stats("x", 200, 90, 0.09, 3, 0.1, 0.1, 50, 0.2).passes(), "mean below +0.10R");
        assertFalse(new Stats("x", 200, 90, 0.3, 1.9, 0.3, 0.3, 50, 0.4).passes(), "t below 2");
    }

    @Test
    void decisionPrefersTheTenThirtyCutOff() {
        Stats pass = new Stats("p", 200, 150, 0.2, 2.5, 0.2, 0.2, 50, 0.3);
        Stats fail = new Stats("f", 200, 150, 0.0, 0.1, 0.1, -0.1, 40, 0.1);
        List<Stats> s = new ArrayList<>(List.of(fail, pass, pass));
        for (int i = 3; i < IntradayFixStudy.VARIANTS.size(); i++) {
            s.add(fail);
        }
        s.set(1, new Stats(v(1).name(), 200, 150, 0.2, 2.5, 0.2, 0.2, 50, 0.3));
        s.set(2, new Stats(v(2).name(), 200, 150, 0.2, 2.5, 0.2, 0.2, 50, 0.3));
        assertTrue(IntradayFixStudy.decision(s).startsWith("DECISION  FIX_1030"));
        s.set(1, new Stats(v(1).name(), 200, 150, 0.0, 0.1, 0.1, -0.1, 40, 0.1));
        assertTrue(IntradayFixStudy.decision(s).startsWith("DECISION  FIX_1000"));
        s.set(2, new Stats(v(2).name(), 200, 150, 0.0, 0.1, 0.1, -0.1, 40, 0.1));
        assertTrue(IntradayFixStudy.decision(s).startsWith("DECISION  NEITHER"));
    }

    @Test
    void marketHoursGuard() {
        assertTrue(IntradayFixStudy.marketHours(LocalDateTime.of(2026, 11, 18, 9, 0)));
        assertTrue(IntradayFixStudy.marketHours(LocalDateTime.of(2026, 11, 18, 15, 34)));
        assertFalse(IntradayFixStudy.marketHours(LocalDateTime.of(2026, 11, 18, 15, 35)));
        assertFalse(IntradayFixStudy.marketHours(LocalDateTime.of(2026, 11, 21, 11, 0)), "Saturday");
    }
}
