package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.replay.ExecutionTimingStudy.Bar;
import com.ram.trading.signal.engine.service.replay.ExecutionTimingStudy.DayPrices;
import com.ram.trading.signal.engine.service.replay.ExecutionTimingStudy.Row;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTimingStudyTest {

    /** A session of 1-minute bars from 09:15 to 15:29, priced by minute index, volume 1. */
    private static List<Bar> day(java.util.function.IntToDoubleFunction price) {
        List<Bar> bars = new ArrayList<>();
        LocalTime t = LocalTime.of(9, 15);
        for (int i = 0; !t.isAfter(LocalTime.of(15, 29)); i++, t = t.plusMinutes(1)) {
            double p = price.applyAsDouble(i);
            bars.add(new Bar(t, p, 1, p * 1.001, p * 0.999));
        }
        return bars;
    }

    @Test
    void pricesAreTakenAtEachTimeAndTheSplitAveragesNineFifteenMinutePoints() {
        DayPrices d = ExecutionTimingStudy.prices(day(i -> 100 + i * 0.01));   // rises 1 paisa a minute
        assertEquals(100.05, d.price()[0], 1e-9, "09:20 is minute 5");
        assertEquals(100.15, d.price()[1], 1e-9, "09:30 is minute 15");
        // Split: 09:30..11:30 every 15 minutes = minutes 15..135, average minute 75.
        assertEquals(100.75, d.price()[8], 1e-9);
        // VWAP with equal volume = the average close over 375 minutes (minute 187).
        assertEquals(100 + 187 * 0.01, d.vwap(), 1e-9);
        assertEquals(20.0, d.rangeBps()[0], 1e-6, "high-low of 0.2% = 20 bps");
    }

    @Test
    void monthsAreFetchedInSevenDayChunksAndAShortSampleIsInvalid() {
        var c = ExecutionTimingStudy.chunks(new LocalDate[]{LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30)});
        assertEquals(5, c.size());
        assertEquals(LocalDate.of(2026, 10, 7), c.get(0)[1]);
        assertEquals(LocalDate.of(2026, 10, 29), c.get(4)[0]);
        assertEquals(LocalDate.of(2026, 10, 30), c.get(4)[1]);

        List<Row> six = List.of(new Row("09:20 (now)", 6, 0, 0, 0, 0, 0, null, false),
                new Row("15:20", 6, 0, 54, 2.4, 77, 31, null, true));
        String text = ExecutionTimingStudy.report(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 11, 10), 13, 910, six, six);
        assertTrue(text.contains("INVALID: only 6 sessions"), text);
        assertFalse(text.contains("SWITCH"), text);
    }

    @Test
    void aRisingDayMakesEarlyBuysCheapAndEarlySellsDear() {
        DayPrices d = ExecutionTimingStudy.prices(day(i -> 100 + i * 0.01));
        assertTrue(ExecutionTimingStudy.cost(d, 0, true) < 0, "buying at 09:20 below the VWAP");
        assertTrue(ExecutionTimingStudy.cost(d, 0, false) > 0, "selling at 09:20 below the VWAP costs");
        assertTrue(ExecutionTimingStudy.cost(d, 7, false) < 0, "selling at 15:20, above the VWAP, gains");
    }

    @Test
    void anOptionPassesOnlyWhenBetterInBothHalvesWithEnoughT() {
        // Sell side on rising days: 15:20 beats 09:20 every day, with some noise.
        ExecutionTimingStudy.Side sells = new ExecutionTimingStudy.Side();
        LocalDate d0 = LocalDate.of(2026, 1, 1);
        for (int k = 0; k < 40; k++) {
            double slope = 0.01 + (k % 3) * 0.002;
            sells.add(d0.plusDays(k), ExecutionTimingStudy.prices(day(i -> 100 + i * slope)), false);
        }
        List<Row> rows = ExecutionTimingStudy.evaluate(sells);
        assertFalse(rows.get(0).passes(), "09:20 is the baseline");
        assertTrue(rows.get(7).passes(), "15:20: " + rows.get(7));
        assertTrue(ExecutionTimingStudy.decision(rows).startsWith("SWITCH to "));

        // Flat days: nothing beats 09:20 by 5 bps.
        ExecutionTimingStudy.Side flat = new ExecutionTimingStudy.Side();
        for (int k = 0; k < 40; k++) {
            flat.add(d0.plusDays(k), ExecutionTimingStudy.prices(day(i -> 100)), true);
        }
        assertEquals("KEEP 09:20 (no option passed)", ExecutionTimingStudy.decision(ExecutionTimingStudy.evaluate(flat)));
    }
}
