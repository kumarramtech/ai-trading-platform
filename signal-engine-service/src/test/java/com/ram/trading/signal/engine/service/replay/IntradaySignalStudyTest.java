package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntradaySignalStudyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    /** A full session from {@code open}, moving {@code perMinute} rupees each minute. */
    private static List<MinuteCandle> day(String symbol, double open, double perMinute) {
        List<MinuteCandle> list = new ArrayList<>();
        for (int i = 0; i < 375; i++) {
            double p = open + i * perMinute;
            list.add(MinuteCandle.builder().symbol(symbol).minute(DAY.atTime(LocalTime.of(9, 15).plusMinutes(i)))
                    .open(i == 0 ? open : p - perMinute).high(Math.max(p, p - perMinute) + 0.01)
                    .low(Math.min(p, p - perMinute) - 0.01).close(p).volume(1000L).build());
        }
        return list;
    }

    private static List<IntradaySignalStudy.Trade> run(Map<String, List<MinuteCandle>> series) throws Exception {
        return IntradaySignalStudy.replayDay(DAY, series, day("NIFTY", 20000, 0), dailyBars(series.keySet()),
                50, 5, 0.03, LocalTime.of(15, 0), new IntradayChargesCalculator());
    }

    @Test
    void buysTheStrongestAndShortsTheWeakestAgainstNiftyAndHoldsToTheExit() throws Exception {
        Map<String, List<MinuteCandle>> series = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            // S0..S9 rise all day, S30..S39 fall, the rest are flat.
            double drift = i < 10 ? 0.01 : i >= 30 ? -0.01 : 0;
            series.put("S" + i, day("S" + i, 100, drift));
        }

        List<IntradaySignalStudy.Trade> trades = run(series);

        List<IntradaySignalStudy.Trade> longs = trades.stream()
                .filter(t -> t.rule().equals("REL_STRENGTH 09:45 -> 15:00 top10 LONG")).toList();
        List<IntradaySignalStudy.Trade> shorts = trades.stream()
                .filter(t -> t.rule().equals("REL_STRENGTH 09:45 -> 15:00 bottom10 SHORT")).toList();
        assertEquals(10, longs.size());
        assertEquals(10, shorts.size());
        // 09:45 -> 14:59 is 314 minutes of +0.01 on ~100.3: about +313 bps raw.
        longs.forEach(t -> assertEquals(314 * 0.01 / 100.30 * 1e4, t.rawBps(), 1.0));
        shorts.forEach(t -> assertTrue(t.rawBps() > 300 && !t.buy()));
        // Gross is raw less 0.03% slippage each way (~6 bps); net is lower again.
        longs.forEach(t -> assertTrue(t.grossBps() < t.rawBps() - 5.5 && t.netBps() < t.grossBps()));

        // Flat stocks only appear in the baseline.
        assertEquals(40, trades.stream().filter(t -> t.rule().startsWith("ALL LONG")).count());
    }

    @Test
    void fadesABigGapAtNineThirty() throws Exception {
        Map<String, List<MinuteCandle>> series = new HashMap<>();
        series.put("GAPPER", day("GAPPER", 104, -0.005));   // opens 4% above yesterday's 100, drifts down

        List<IntradaySignalStudy.Trade> gap = run(series).stream()
                .filter(t -> t.rule().equals("GAP fade >= 3%")).toList();

        assertEquals(1, gap.size());
        assertTrue(!gap.get(0).buy() && gap.get(0).rawBps() > 0, "shorted the gap up and it faded: " + gap.get(0));
    }

    @Test
    void ignoresAStalePrice() {
        List<MinuteCandle> series = day("X", 100, 0).subList(0, 10);   // last candle 09:24
        assertEquals(100.0, IntradaySignalStudy.closeAt(series, LocalTime.of(9, 26)));
        assertNull(IntradaySignalStudy.closeAt(series, LocalTime.of(9, 30)));
    }

    @Test
    void reportsCandidatesOnlyWhenBothHalvesAgree() {
        List<IntradaySignalStudy.Trade> trades = new ArrayList<>();
        for (int d = 0; d < 20; d++) {
            LocalDate day = DAY.plusDays(d);
            trades.add(new IntradaySignalStudy.Trade(day, "STEADY", true, 40 + d % 3, 34 + d % 3, 20));
            // Wins only in the first half.
            trades.add(new IntradaySignalStudy.Trade(day, "FIRST HALF ONLY", true, d < 10 ? 80 : -20, d < 10 ? 74 : -26, 0));
        }

        String report = IntradaySignalStudy.render(trades, 0.03, new IntradayChargesCalculator());

        assertTrue(report.lines().anyMatch(l -> l.startsWith("STEADY") && l.endsWith("CANDIDATE")), report);
        assertTrue(report.lines().anyMatch(l -> l.startsWith("FIRST HALF ONLY") && l.endsWith("-")), report);
    }

    /** 20 sessions closing at 100 on 1M shares (Rs 10 crore turnover), range 99-101. */
    private static DailyBars dailyBars(java.util.Set<String> symbols) throws Exception {
        Map<String, List<DailyBars.Bar>> bars = new HashMap<>();
        for (String symbol : symbols) {
            List<DailyBars.Bar> list = new ArrayList<>();
            for (int i = 20; i >= 1; i--) {
                list.add(new DailyBars.Bar(DAY.minusDays(i), 101, 99, 100, 1_000_000));
            }
            bars.put(symbol, list);
        }
        Constructor<DailyBars> c = DailyBars.class.getDeclaredConstructor(Map.class);
        c.setAccessible(true);
        return c.newInstance(bars);
    }
}
