package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.replay.MomentumSignalService.MonthlySignal;
import com.ram.trading.signal.engine.service.replay.SwingBacktestService.Series;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomentumSignalServiceTest {

    private static final int SESSIONS = 200;
    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);

    /** Daily closes growing by {@code dailyPct} with a small alternating wobble (so volatility is not zero). */
    private static Series series(String symbol, double start, double dailyPct, double volume) {
        LocalDate[] d = new LocalDate[SESSIONS];
        double[] o = new double[SESSIONS], h = new double[SESSIONS], l = new double[SESSIONS],
                c = new double[SESSIONS], v = new double[SESSIONS];
        double p = start;
        for (int i = 0; i < SESSIONS; i++) {
            p *= 1 + dailyPct / 100 + (i % 2 == 0 ? 0.002 : -0.002);
            d[i] = FIRST.plusDays(i);
            o[i] = p;
            h[i] = p * 1.01;
            l[i] = p * 0.99;
            c[i] = p;
            v[i] = volume;
        }
        return new Series(symbol, d, o, h, l, c, v);
    }

    private static MonthlySignal build(Map<String, Series> data, Series nifty, List<String> previous) {
        return MomentumSignalService.build(data, nifty, previous, YearMonth.of(2026, 7), 30000, 2, 126, 21, 3, 100, 50);
    }

    @Test
    void holdsTheStrongestLiquidStocksAndSaysWhatToBuyAndSell() {
        Map<String, Series> data = Map.of(
                "FAST", series("FAST", 100, 0.30, 1_000_000),
                "MID", series("MID", 100, 0.15, 1_000_000),
                "SLOW", series("SLOW", 100, 0.05, 1_000_000),
                "ILLIQUID", series("ILLIQUID", 100, 0.50, 10),       // strongest, but not in the top 3 by turnover
                "PENNY", series("PENNY", 10, 0.40, 50_000_000));     // liquid, but under the Rs 50 floor
        Series nifty = series("NIFTY", 20000, 0.05, 0);

        MonthlySignal s = build(data, nifty, List.of("SLOW", "MID"));

        assertTrue(s.riskOn());
        assertEquals(List.of("FAST", "MID"), s.target().stream().map(MomentumSignalService.Pick::symbol).toList());
        assertEquals(List.of("FAST"), s.buys());
        assertEquals(List.of("SLOW"), s.sells());
        assertEquals(List.of("MID"), s.holds());
        // Rs 30,000 over 2 stocks: Rs 15,000 each, whole shares at the last close.
        MomentumSignalService.Pick fast = s.target().get(0);
        assertEquals((int) Math.floor(15000 / fast.close()), fast.quantity());
        assertEquals(nifty.date()[SESSIONS - 1], s.signalDay());
    }

    @Test
    void sellsEverythingWhenNiftyIsBelowItsAverage() {
        Map<String, Series> data = Map.of("FAST", series("FAST", 100, 0.30, 1_000_000));
        Series falling = series("NIFTY", 20000, -0.20, 0);

        MonthlySignal s = build(data, falling, List.of("FAST", "MID"));

        assertFalse(s.riskOn());
        assertTrue(s.target().isEmpty());
        assertEquals(List.of("FAST", "MID"), s.sells());
        assertTrue(s.buys().isEmpty());
    }
}
