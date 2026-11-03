package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskBookStudyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private final IntradayChargesCalculator charges = new IntradayChargesCalculator();

    /** Flat at 100 until 09:45, then {@code perMinute} rupees a minute to 15:29. */
    private static List<MinuteCandle> series(String symbol, double perMinute) {
        List<MinuteCandle> list = new ArrayList<>();
        for (int i = 0; i < 375; i++) {
            LocalTime t = LocalTime.of(9, 15).plusMinutes(i);
            int moving = Math.max(0, i - 30);
            double p = 100 + moving * perMinute;
            double prev = 100 + Math.max(0, moving - 1) * perMinute;
            list.add(MinuteCandle.builder().symbol(symbol).minute(DAY.atTime(t)).open(prev)
                    .high(Math.max(p, prev) + 0.01).low(Math.min(p, prev) - 0.01).close(p).volume(1000L).build());
        }
        return list;
    }

    private static RiskBookStudy.Book book(double dailyStop, double takeProfit) {
        return new RiskBookStudy.Book("T", 3, 0, 40_000, 2, 0, dailyStop, takeProfit, false, LocalTime.of(15, 0));
    }

    private static List<RiskBookStudy.Pick> shorts(double perMinute) {
        List<RiskBookStudy.Pick> picks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            picks.add(new RiskBookStudy.Pick("S" + i, series("S" + i, perMinute), false, 2));
        }
        return picks;
    }

    @Test
    void dayStopClosesTheBookBeforeThePositionStops() {
        // Three shorts squeezed together: the book reaches -1500 near -1.2% each, before any 2% stop.
        RiskBookStudy.DayResult r = RiskBookStudy.simulate(DAY, book(1500, 0), shorts(0.02), 0.03, charges);

        assertTrue(r.halted());
        assertTrue(r.netRs() <= -1500 && r.netRs() > -1600, "loss held near the day stop: " + r.netRs());
    }

    @Test
    void withoutADayStopEachPositionStopsOutAtTwoPercent() {
        RiskBookStudy.DayResult r = RiskBookStudy.simulate(DAY, book(0, 0), shorts(0.02), 0.03, charges);

        assertFalse(r.halted());
        // 3 x (2% of ~Rs 40k + slippage + charges) ~ -2500
        assertTrue(r.netRs() < -2400 && r.netRs() > -2700, "three stop-outs: " + r.netRs());
    }

    @Test
    void takeProfitLocksTheDayOnceReached() {
        RiskBookStudy.DayResult r = RiskBookStudy.simulate(DAY, book(1500, 2000), shorts(-0.02), 0.03, charges);

        assertTrue(r.tookProfit() && !r.halted());
        assertTrue(r.netRs() >= 2000 && r.netRs() < 2150, "profit locked: " + r.netRs());
    }

    @Test
    void heldToTheExitWhenNeitherLimitIsHit() {
        // -0.001 a minute: ~-0.3% by 14:59, a small win on each short.
        RiskBookStudy.DayResult r = RiskBookStudy.simulate(DAY, book(1500, 0), shorts(-0.001), 0.03, charges);

        assertFalse(r.halted() || r.tookProfit());
        assertEquals(3, r.trades());
        assertTrue(r.netRs() > 0, "small win after costs: " + r.netRs());
    }

    @Test
    void worstWindowAndDrawdown() {
        double[] days = {500, -300, -400, 200, -600, 100};

        assertEquals(-1100, RiskBookStudy.worstWindow(days, 4), 0.01);
        assertEquals(-1100, RiskBookStudy.maxDrawdown(days), 0.01);
    }

    @Test
    void reportMarksABookThatClearsTheBar() {
        List<RiskBookStudy.DayResult> results = new ArrayList<>();
        for (int d = 0; d < 40; d++) {
            results.add(new RiskBookStudy.DayResult(DAY.plusDays(d), "GOOD", d % 4 == 0 ? -800 : 700, 3, false, false));
            results.add(new RiskBookStudy.DayResult(DAY.plusDays(d), "BAD", d % 2 == 0 ? -5000 : 5600, 3, false, false));
        }

        String report = RiskBookStudy.render(results);

        assertTrue(report.lines().anyMatch(l -> l.startsWith("GOOD") && l.endsWith("PASS")), report);
        assertTrue(report.lines().anyMatch(l -> l.startsWith("BAD") && !l.endsWith("PASS")), report);
    }
}
