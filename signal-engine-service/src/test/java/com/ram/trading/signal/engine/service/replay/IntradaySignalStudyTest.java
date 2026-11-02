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
        return run(series, null);
    }

    private static List<IntradaySignalStudy.Trade> run(Map<String, List<MinuteCandle>> series, Double prevNiftyClose)
            throws Exception {
        return IntradaySignalStudy.replayDay(DAY, series, day("NIFTY", 20000, 0), prevNiftyClose, dailyBars(series.keySet()),
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

    @Test
    void study2FadesALargeCapOverreactionAndALightVolumeMove() throws Exception {
        Map<String, List<MinuteCandle>> series = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            series.put("S" + i, day("S" + i, 100, 0));
        }
        series.put("DROP", day("DROP", 100, -0.1));   // -3% by 09:45 on light volume (30k of 1M)
        series.put("POP", day("POP", 100, 0.1));

        List<IntradaySignalStudy.Trade> trades = run(series);

        assertEquals(1, trades.stream().filter(t -> t.rule().startsWith("S2 OVERREACTION")
                && t.rule().endsWith("LONG") && t.buy()).count());
        assertEquals(1, trades.stream().filter(t -> t.rule().startsWith("S2 OVERREACTION")
                && t.rule().endsWith("SHORT") && !t.buy()).count());
        assertTrue(trades.stream().anyMatch(t -> t.rule().startsWith("S2 NOISE weakest10") && t.buy()));
        assertTrue(trades.stream().anyMatch(t -> t.rule().startsWith("S2 NOISE strongest10") && !t.buy()));
        assertTrue(trades.stream().noneMatch(t -> t.rule().startsWith("S2 NEWS")));
        assertEquals(10, trades.stream().filter(t -> t.rule().equals("S2 weakest10 09:45 -> 12:00 LONG")).count());
    }

    @Test
    void study2FollowsNiftysEarlyMoveInTheLastHalfHour() throws Exception {
        Map<String, List<MinuteCandle>> series = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            series.put("S" + i, day("S" + i, 100, 0));
        }

        // NIFTY at 20000 against a 19900 close: +0.5% by 09:45 -> the 10 most traded, long.
        List<IntradaySignalStudy.Trade> up = run(series, 19900.0).stream()
                .filter(t -> t.rule().startsWith("S2 NIFTY_MOMENTUM")).toList();
        assertEquals(10, up.size());
        assertTrue(up.stream().allMatch(IntradaySignalStudy.Trade::buy));

        // +0.1% is too small; no previous close means no trade.
        assertTrue(run(series, 19980.0).stream().noneMatch(t -> t.rule().startsWith("S2 NIFTY_MOMENTUM")));
        assertTrue(run(series).stream().noneMatch(t -> t.rule().startsWith("S2 NIFTY_MOMENTUM")));
    }

    @Test
    void reportShowsNetAtTheLargerSize() {
        List<IntradaySignalStudy.Trade> trades = new ArrayList<>();
        for (int d = 0; d < 20; d++) {
            trades.add(new IntradaySignalStudy.Trade(DAY.plusDays(d), "R", true, 20, 14, 1));
        }
        String report = IntradaySignalStudy.render(trades, 0.03, new IntradayChargesCalculator());
        // One Rs 2.5 lakh trade pays ~5 bps of charges (brokerage capped at Rs 20 per order), not ~13.
        String row = report.lines().filter(l -> l.startsWith("R ")).findFirst().orElseThrow();
        double netBig = Double.parseDouble(row.substring(58).trim().split("\\s+")[5]);
        assertTrue(netBig > 7 && netBig < 11, row);
    }

    @Test
    void round2BuysTheWeakestAcrossTheGridAndStopsOutAFallingStock() throws Exception {
        Map<String, List<MinuteCandle>> series = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            series.put("S" + i, day("S" + i, 100, 0.001 * i));
        }
        series.put("FALL", day("FALL", 100, -0.02));   // weakest all day: -0.6% by 09:45, -6% by 14:15

        List<IntradaySignalStudy.Trade> trades = run(series);

        // 27 grid rules, each buying its count of the weakest.
        assertEquals(27, trades.stream().filter(t -> t.rule().startsWith("R2 weakest")).map(IntradaySignalStudy.Trade::rule)
                .distinct().count());
        assertEquals(20, trades.stream().filter(t -> t.rule().equals("R2 weakest20 09:30 -> 12:00 LONG")).count());
        assertTrue(trades.stream().filter(t -> t.rule().startsWith("R2")).allMatch(IntradaySignalStudy.Trade::buy));

        // FALL with no stop loses ~5.3% by 14:59; the 1.5% stop caps it near -1.5%.
        double noStop = trades.stream().filter(t -> t.rule().equals("R2 weakest5  09:45 -> 15:00 LONG"))
                .mapToDouble(IntradaySignalStudy.Trade::rawBps).min().orElseThrow();
        double stopped = trades.stream().filter(t -> t.rule().equals("R2 base | stop 1.5%"))
                .mapToDouble(IntradaySignalStudy.Trade::rawBps).min().orElseThrow();
        assertTrue(noStop < -500, "no stop: " + noStop);
        assertEquals(-150, stopped, 3);

        // Flat NIFTY: every base trade lands in the flat/down bucket.
        assertEquals(10, trades.stream().filter(t -> t.rule().equals("R2 base | NIFTY flat/down at 09:45")).count());
        // No previous NIFTY close, no prev-close ranking.
        assertTrue(trades.stream().noneMatch(t -> t.rule().equals("R2 base | ranked from prev close")));
    }

    @Test
    void dailyRiskReportsTheWorstDayAndTheDeepestDrawdown() {
        // +100, -100 (x2 trades = -Rs 1000), +20 bps: Rs +500, -1000, +100
        List<IntradaySignalStudy.Trade> trades = List.of(
                new IntradaySignalStudy.Trade(DAY, "R", true, 0, 0, 100),
                new IntradaySignalStudy.Trade(DAY.plusDays(1), "R", true, 0, 0, -100),
                new IntradaySignalStudy.Trade(DAY.plusDays(1), "R", true, 0, 0, -100),
                new IntradaySignalStudy.Trade(DAY.plusDays(2), "R", true, 0, 0, 20));

        double[] risk = IntradaySignalStudy.dailyRisk(trades);

        assertEquals(-1000, risk[0], 0.01);
        assertEquals(-1000, risk[1], 0.01);
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
