package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.FadeBothMarketsStudy.Entry;
import com.ram.trading.signal.engine.service.replay.FadeBothMarketsStudy.Rule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FadeBothMarketsStudyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final int ENTRY_MINUTE = 30;   // 09:45
    private final IntradayChargesCalculator charges = new IntradayChargesCalculator();

    private static List<MinuteCandle> series(String symbol, IntToDoubleFunction price) {
        List<MinuteCandle> list = new ArrayList<>();
        for (int i = 0; i < 375; i++) {
            double p = price.applyAsDouble(i);
            double prev = price.applyAsDouble(Math.max(0, i - 1));
            list.add(MinuteCandle.builder().symbol(symbol).minute(DAY.atTime(LocalTime.of(9, 15).plusMinutes(i)))
                    .open(prev).high(Math.max(p, prev) + 0.01).low(Math.min(p, prev) - 0.01).close(p).volume(1000L).build());
        }
        return list;
    }

    /** Flat at {@code level} to 09:45, then {@code perMinute} a minute. */
    private static List<MinuteCandle> afterEntry(String symbol, double level, double perMinute) {
        return series(symbol, i -> level + Math.max(0, i - ENTRY_MINUTE) * perMinute);
    }

    private List<FadeLookAheadStudy.Trade> run(Rule rule, List<MinuteCandle> stock, List<MinuteCandle> nifty) {
        return FadeBothMarketsStudy.ruleTrades(DAY, rule, List.of(stock), nifty, 24000.0, 0.03, charges);
    }

    @Test
    void liveRowMatchesTheRound4Short() {
        List<MinuteCandle> stock = afterEntry("S", 100, -0.01);
        FadeLookAheadStudy.Trade round4 = FadeLookAheadStudy.shortTrade(DAY, "r", stock, 0, 0.03, charges);

        FadeLookAheadStudy.Trade r0 = run(FadeBothMarketsStudy.RULES.get(0), stock, afterEntry("NIFTY", 24000, 0)).get(0);

        assertEquals(round4.grossBps(), r0.grossBps(), 1e-9);
        assertEquals(round4.netRs(), r0.netRs(), 1e-9);
    }

    @Test
    void hedgeRemovesTheMarketsMove() {
        // Stock and NIFTY both rise 3% after 09:45: the plain short loses it, the hedged one only pays costs.
        List<MinuteCandle> stock = afterEntry("S", 100, 3.0 / 314);       // 314 minutes from 09:45 to 14:59
        List<MinuteCandle> nifty = afterEntry("NIFTY", 24000, 720.0 / 314);

        FadeLookAheadStudy.Trade plain = run(Rule.of("p", Entry.AT_0945, 0, false), stock, nifty).get(0);
        FadeLookAheadStudy.Trade hedged = run(Rule.of("h", Entry.AT_0945, 0, true), stock, nifty).get(0);

        assertEquals(-300 - 6, plain.grossBps(), 3);
        assertEquals(-12, hedged.grossBps(), 3);       // two legs, 2 x 3 bps of slippage each
        assertTrue(hedged.netRs() < 0 && hedged.netRs() > plain.netRs());
    }

    @Test
    void hedgeIsOneOrderForLegsSharingEntryAndExit() {
        List<MinuteCandle> nifty = afterEntry("NIFTY", 24000, 0);
        List<MinuteCandle> flat = afterEntry("S", 100, 0);
        Rule hedge = Rule.of("h", Entry.AT_0945, 0, true);

        double one = run(hedge, flat, nifty).get(0).netRs();
        List<FadeLookAheadStudy.Trade> five = FadeBothMarketsStudy.ruleTrades(DAY, hedge,
                List.of(flat, flat, flat, flat, flat), nifty, 24000.0, 0.03, charges);

        assertEquals(5, five.size());
        // Shared brokerage: each of five costs less than a single hedged short alone.
        assertTrue(five.get(0).netRs() > one, five.get(0).netRs() + " vs " + one);
    }

    @Test
    void waitsForTheTurnAndFillsAtTheNextOpen() {
        // Up to 09:45, keeps rising to 10:00, then falls through its VWAP.
        List<MinuteCandle> turning = series("S", i -> i <= 45 ? 100 + i * 0.1 : 104.5 - (i - 45) * 0.2);
        FadeBothMarketsStudy.Entered vwap = FadeBothMarketsStudy.entered(turning, Entry.BELOW_VWAP);

        assertNotNull(vwap);
        assertTrue(vwap.signal().isAfter(LocalTime.of(10, 0)) && !vwap.signal().isAfter(LocalTime.of(11, 0)), "" + vwap);
        int signal = (int) java.time.Duration.between(LocalTime.of(9, 15), vwap.signal()).toMinutes();
        assertEquals(turning.get(signal + 1).getOpen(), vwap.price(), 1e-9);
        assertTrue(turning.get(signal).getClose() < 104.5, "the signal is a close already known");
    }

    @Test
    void neverShortsAStockThatKeepsRising() {
        List<MinuteCandle> rising = series("S", i -> 100 + i * 0.05);

        assertNull(FadeBothMarketsStudy.entered(rising, Entry.BELOW_VWAP));
        assertNull(FadeBothMarketsStudy.entered(rising, Entry.BELOW_15MIN_LOW));
        assertTrue(run(Rule.of("w", Entry.BELOW_VWAP, 0, false), rising, afterEntry("NIFTY", 24000, 0)).isEmpty());
    }

    @Test
    void stopCapsASqueeze() {
        List<MinuteCandle> squeeze = afterEntry("S", 100, 0.02);

        FadeLookAheadStudy.Trade stopped = run(Rule.of("s", Entry.AT_0945, 3, false), squeeze, afterEntry("NIFTY", 24000, 0)).get(0);

        assertEquals(-300 - 6, stopped.grossBps(), 2);
    }

    @Test
    void marketFilterActsOnlyOnWhatIsKnownAt0945() {
        List<MinuteCandle> stock = afterEntry("S", 100, 0);
        // 09:15 open 24000, 09:45 at 24120 (+0.5%), then falling all day: the later fall must not matter.
        List<MinuteCandle> strongOpen = series("NIFTY", i -> i <= ENTRY_MINUTE ? 24000 + i * 4 : 24120 - (i - ENTRY_MINUTE) * 5);
        Rule fromOpen = new Rule("m2", Entry.AT_0945, 0, false, Double.NaN, 0.25);
        Rule fromPrev = new Rule("m1", Entry.AT_0945, 0, false, 0.75, Double.NaN);

        assertTrue(FadeBothMarketsStudy.ruleTrades(DAY, fromOpen, List.of(stock), strongOpen, 24000.0, 0.03, charges).isEmpty());
        assertEquals(1, FadeBothMarketsStudy.ruleTrades(DAY, fromPrev, List.of(stock), strongOpen, 24000.0, 0.03, charges).size());
        assertTrue(FadeBothMarketsStudy.ruleTrades(DAY, fromPrev, List.of(stock), strongOpen, 23900.0, 0.03, charges).isEmpty());
    }

    @Test
    void dayTypesAreMeasuredFromThePreviousCloseAndFrom0945() {
        List<MinuteCandle> nifty = series("NIFTY", i -> i <= ENTRY_MINUTE ? 24240 : 24240 - (i - ENTRY_MINUTE) * 0.2);

        FadeBothMarketsStudy.Day day = FadeBothMarketsStudy.evaluate(DAY, List.of(), nifty, 24000.0, 0.03, charges);

        assertTrue(day.wholeMove() > 0.5, "a gap-up day is an up day: " + day.wholeMove());
        assertTrue(day.afterEntryMove() < 0, "but it fell after the entry: " + day.afterEntryMove());
    }

    @Test
    void reportPassesOnlyARowThatEarnsOnEveryKindOfDay() {
        List<FadeBothMarketsStudy.Day> days = new ArrayList<>();
        for (int d = 0; d < 120; d++) {
            double move = d % 2 == 0 ? 1.0 : -1.0;
            List<FadeLookAheadStudy.Trade> trades = new ArrayList<>();
            for (int k = 0; k < 5; k++) {
                double all = (d + k) % 4 == 0 ? -30 : 70;   // 45 bps on every kind of day
                trades.add(new FadeLookAheadStudy.Trade(DAY.plusDays(d), "H1 hedged with NIFTY", all, all, all * 4));
                double upOnly = move > 0 ? all : -all;      // earns only on up days
                trades.add(new FadeLookAheadStudy.Trade(DAY.plusDays(d), "S3 stop 3%", upOnly, upOnly, upOnly * 4));
            }
            days.add(new FadeBothMarketsStudy.Day(DAY.plusDays(d), move, move, trades));
        }

        String report = FadeBothMarketsStudy.render(days);

        assertTrue(report.lines().anyMatch(l -> l.startsWith("H1 ") && l.endsWith("  PASS")), report);
        assertTrue(report.lines().anyMatch(l -> l.startsWith("S3 ") && l.contains("down")), report);
        assertTrue(report.lines().anyMatch(l -> l.startsWith("R0 ")), "every rule is listed, traded or not");
    }
}
