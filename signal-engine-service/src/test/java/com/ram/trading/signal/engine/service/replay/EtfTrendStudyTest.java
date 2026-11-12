package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.replay.EtfTrendStudy.Book;
import com.ram.trading.signal.engine.service.replay.EtfTrendStudy.Signal;
import com.ram.trading.signal.engine.service.replay.EtfTrendStudy.Stats;
import com.ram.trading.signal.engine.service.replay.SwingBacktestService.Series;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtfTrendStudyTest {

    private static LocalDate[] weekdays(LocalDate from, int n) {
        LocalDate[] d = new LocalDate[n];
        LocalDate day = from;
        for (int i = 0; i < n; i++) {
            while (day.getDayOfWeek().getValue() > 5) {
                day = day.plusDays(1);
            }
            d[i] = day;
            day = day.plusDays(1);
        }
        return d;
    }

    private static Series series(String symbol, LocalDate[] d, IntToDoubleFunction price) {
        double[] c = new double[d.length];
        for (int i = 0; i < d.length; i++) {
            c[i] = price.applyAsDouble(i);
        }
        return new Series(symbol, d, c, c, c, c, c);
    }

    @Test
    void trendSignalUsesTheTwoHundredDayAverage() {
        LocalDate[] d = weekdays(LocalDate.of(2016, 1, 1), 400);
        Series up = series("X", d, i -> 100 + i);
        Series down = series("Y", d, i -> 500 - i);
        assertFalse(EtfTrendStudy.inTrend(up, d[150], Signal.SMA200), "not known before 200 sessions");
        assertTrue(EtfTrendStudy.inTrend(up, d[300], Signal.SMA200));
        assertFalse(EtfTrendStudy.inTrend(down, d[300], Signal.SMA200));
        assertTrue(EtfTrendStudy.inTrend(up, d[300], Signal.TWELVE_MONTH));
        assertFalse(EtfTrendStudy.inTrend(down, d[300], Signal.TWELVE_MONTH));
    }

    @Test
    void risingEtfsAreHeldFallingOnesSitInCash() {
        LocalDate[] d = weekdays(LocalDate.of(2016, 1, 1), 900);
        Map<String, Series> etfs = new HashMap<>();
        for (String e : EtfTrendStudy.ETFS) {
            etfs.put(e, series(e, d, i -> 100 * Math.pow(1.0005, i)));
        }
        LocalDate[] calendar = SwingBacktestService.since(series("NIFTY", d, i -> 1), d[400]).date();
        Book up = EtfTrendStudy.simulate(etfs, calendar, Signal.SMA200, false);
        assertEquals(6, up.entries(), "each ETF bought once and held");
        assertEquals(0, up.exits());
        assertTrue(EtfTrendStudy.stats(up.months()).cagrPct() > 10, "about 13%/yr: " + EtfTrendStudy.stats(up.months()));

        for (String e : EtfTrendStudy.ETFS) {
            etfs.put(e, series(e, d, i -> 1000 * Math.pow(0.9995, i)));
        }
        Book down = EtfTrendStudy.simulate(etfs, calendar, Signal.SMA200, false);
        assertEquals(0, down.entries());
        assertEquals(6.5, EtfTrendStudy.stats(down.months()).cagrPct(), 0.3, "all cash at the liquid-fund yield");
        Book hold = EtfTrendStudy.simulate(etfs, calendar, Signal.SMA200, true);
        assertTrue(EtfTrendStudy.stats(hold.months()).cagrPct() < 0, "buy and hold takes the fall");
    }

    @Test
    void mixHalvesAndPassRule() {
        TreeMap<LocalDate, Double> mom = new TreeMap<>();
        TreeMap<LocalDate, Double> trend = new TreeMap<>();
        for (int k = 0; k < 10; k++) {
            mom.put(LocalDate.of(2020, 1 + k, 1), k == 3 ? -0.10 : 0.02);
            trend.put(LocalDate.of(2020, 1 + k, 1), 0.01);
        }
        TreeMap<LocalDate, Double> mix = EtfTrendStudy.mix(mom, trend);
        assertEquals(-0.067, mix.get(LocalDate.of(2020, 4, 1)), 1e-9);
        assertEquals(5, EtfTrendStudy.part(mix, true).size());
        assertEquals(5, EtfTrendStudy.part(mix, false).size());

        Stats momentum = new Stats(16, 10, -8);
        assertTrue(EtfTrendStudy.passes(new Stats(9, 5, -3), momentum, new Stats(14.5, 7, -6)));
        assertFalse(EtfTrendStudy.passes(new Stats(8, 5, -3), momentum, new Stats(14.5, 7, -6)), "trend too weak");
        assertFalse(EtfTrendStudy.passes(new Stats(9, 5, -3), momentum, new Stats(13.5, 6, -6)), "gives up > 2 points");
        assertFalse(EtfTrendStudy.passes(new Stats(9, 5, -3), momentum, new Stats(15, 10, -6)), "no better ratio");
    }

    @Test
    void chunksMergeByDate() {
        LocalDate[] a = weekdays(LocalDate.of(2020, 12, 28), 5);
        LocalDate[] b = weekdays(LocalDate.of(2021, 1, 1), 3);
        Series m = EtfTrendStudy.merge("X", List.of(series("X", a, i -> 1), series("X", b, i -> 2)));
        assertEquals(7, m.size(), "Dec 28-31, Jan 1 once, Jan 4-5");
        assertEquals(1, m.close()[4], 1e-9, "first part wins a duplicate day");
        assertEquals(LocalDate.of(2021, 1, 5), m.date()[6]);
    }
}
