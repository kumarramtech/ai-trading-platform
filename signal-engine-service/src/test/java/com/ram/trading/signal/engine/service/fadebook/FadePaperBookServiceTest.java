package com.ram.trading.signal.engine.service.fadebook;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.entity.FadePaperTrade;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.OpeningScreen;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FadePaperBookServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private final IntradayChargesCalculator charges = new IntradayChargesCalculator();

    private static FadePaperBookService.Settings settings(double dailyStop) {
        return new FadePaperBookService.Settings(3, 40_000, 2, dailyStop, 0, false, 0.03);
    }

    /** Flat at 100 until 09:45, then {@code perMinute} rupees a minute to 15:29 (as in RiskBookStudyTest). */
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

    private static OpeningScreen.Screen screen(double niftyMove, int candidates) {
        List<OpeningScreen.Ranked> fade = new ArrayList<>();
        for (int i = 0; i < candidates; i++) {
            fade.add(new OpeningScreen.Ranked("S" + i, List.of(), 500 + i, 6.0 - i, 100));
        }
        return new OpeningScreen.Screen(niftyMove, 1800, fade, List.of());
    }

    /** Candles after 09:45 and before {@code before}, as the monitor loads them. */
    private static Map<String, List<MinuteCandle>> bars(List<FadePaperTrade> trades, double perMinute,
                                                        LocalTime from, LocalTime before) {
        Map<String, List<MinuteCandle>> out = new HashMap<>();
        for (FadePaperTrade t : trades) {
            out.put(t.getSymbol(), series(t.getSymbol(), perMinute).stream()
                    .filter(c -> !c.getMinute().toLocalTime().isBefore(from)
                            && c.getMinute().toLocalTime().isBefore(before))
                    .toList());
        }
        return out;
    }

    /** advance to 15:00, then cover the rest at the last close like squareOff. */
    private double runDay(List<FadePaperTrade> trades, double perMinute, FadePaperBookService.Settings s) {
        FadePaperBookService.advance(trades, bars(trades, perMinute, LocalTime.of(9, 46), LocalTime.of(15, 0)), 0, s, charges);
        trades.stream().filter(t -> FadePaperBookService.OPEN.equals(t.getStatus()))
                .forEach(t -> FadePaperBookService.close(t, t.getLastPrice(), DAY.atTime(15, 0), "TIME", s, charges));
        return FadePaperBookService.dayNet(trades);
    }

    @Test
    void opensTheBiggestMoversUpToThePositionLimit() {
        List<FadePaperTrade> trades = FadePaperBookService.open(DAY, screen(0.4, 5), settings(1500), charges);

        assertEquals(List.of("S0", "S1", "S2"), trades.stream().map(FadePaperTrade::getSymbol).toList());
        FadePaperTrade t = trades.get(0);
        assertEquals(99.97, t.getEntryPrice(), 1e-9);       // sold 0.03% under the 09:45 close
        assertEquals(400, t.getQuantity());                  // floor(40000 / 99.97)
        assertEquals(101.97, t.getStopLoss(), 0.01);         // 2% above entry
        assertEquals(501, t.getTurnoverRank());
        assertEquals(DAY.atTime(9, 45), t.getLastMinute());
        assertEquals(FadePaperBookService.OPEN, t.getStatus());
    }

    @Test
    void niftyUpOnlyBookSkipsAFlatOrDownMarket() {
        FadePaperBookService.Settings s = new FadePaperBookService.Settings(3, 40_000, 2, 1500, 0, true, 0.03);

        assertTrue(FadePaperBookService.open(DAY, screen(-0.1, 5), s, charges).isEmpty());
        assertEquals(3, FadePaperBookService.open(DAY, screen(0.2, 5), s, charges).size());
    }

    @Test
    void dayStopCoversEverythingNearTheLimit() {
        List<FadePaperTrade> trades = FadePaperBookService.open(DAY, screen(0.4, 3), settings(1500), charges);

        double net = runDay(trades, 0.02, settings(1500));

        assertTrue(trades.stream().allMatch(t -> "DAY_STOP".equals(t.getExitReason())));
        // Same band as the replay's RiskBookStudyTest for this book and path.
        assertTrue(net <= -1500 && net > -1600, "loss held near the day stop: " + net);
    }

    @Test
    void withoutADayStopEachShortStopsOutAtTwoPercent() {
        List<FadePaperTrade> trades = FadePaperBookService.open(DAY, screen(0.4, 3), settings(0), charges);

        double net = runDay(trades, 0.02, settings(0));

        assertTrue(trades.stream().allMatch(t -> "STOP".equals(t.getExitReason())));
        assertTrue(net < -2400 && net > -2700, "three stop-outs: " + net);
    }

    @Test
    void gapThroughTheStopFillsAtTheOpen() {
        List<FadePaperTrade> trades = FadePaperBookService.open(DAY, screen(0.4, 1), settings(0), charges);
        MinuteCandle gap = MinuteCandle.builder().symbol("S0").minute(DAY.atTime(9, 46))
                .open(104.0).high(104.5).low(103.8).close(104.2).volume(1L).build();

        FadePaperBookService.advance(trades, Map.of("S0", List.of(gap)), 0, settings(0), charges);

        FadePaperTrade t = trades.get(0);
        assertEquals("STOP", t.getExitReason());
        assertEquals(104.03, t.getExitPrice(), 0.01);        // 104 open + 0.03% slippage, not the 101.97 stop
    }

    @Test
    void winnersAreHeldToTheExit() {
        List<FadePaperTrade> trades = FadePaperBookService.open(DAY, screen(0.4, 3), settings(1500), charges);

        double net = runDay(trades, -0.001, settings(1500));

        assertTrue(trades.stream().allMatch(t -> "TIME".equals(t.getExitReason())));
        assertTrue(net > 0, "small win after costs: " + net);
    }

    @Test
    void resumingInChunksMatchesOneRun() {
        List<FadePaperTrade> once = FadePaperBookService.open(DAY, screen(0.4, 3), settings(1500), charges);
        double whole = runDay(once, 0.01, settings(1500));

        // The monitor's view: a few minutes per run, the realized P&L read back each time.
        List<FadePaperTrade> chunked = FadePaperBookService.open(DAY, screen(0.4, 3), settings(1500), charges);
        for (LocalTime from = LocalTime.of(9, 46); from.isBefore(LocalTime.of(15, 0)); from = from.plusMinutes(7)) {
            LocalTime before = from.plusMinutes(7).isAfter(LocalTime.of(15, 0)) ? LocalTime.of(15, 0) : from.plusMinutes(7);
            List<FadePaperTrade> open = chunked.stream().filter(t -> FadePaperBookService.OPEN.equals(t.getStatus())).toList();
            double realized = FadePaperBookService.dayNet(chunked);
            FadePaperBookService.advance(new ArrayList<>(open), bars(open, 0.01, from, before), realized,
                    settings(1500), charges);
        }
        chunked.stream().filter(t -> FadePaperBookService.OPEN.equals(t.getStatus()))
                .forEach(t -> FadePaperBookService.close(t, t.getLastPrice(), DAY.atTime(15, 0), "TIME", settings(1500), charges));

        assertEquals(whole, FadePaperBookService.dayNet(chunked), 0.01);
        assertEquals(once.stream().map(FadePaperTrade::getExitReason).toList(),
                chunked.stream().map(FadePaperTrade::getExitReason).toList());
    }

    @Test
    void labelNamesTheBook() {
        assertEquals("5x40000 stop 2% day 1500",
                new FadePaperBookService.Settings(5, 40_000, 2, 1500, 0, false, 0.03).label());
        assertEquals("5x40000 stop 1.5% day none tp 2000 nifty-up",
                new FadePaperBookService.Settings(5, 40_000, 1.5, 0, 2000, true, 0.03).label());
    }

    private static OpeningScreen.Ranked ranked(String symbol, int rank, double rel, double prevClose) {
        List<MinuteCandle> s = series(symbol, 0);
        return new OpeningScreen.Ranked(symbol, s, rank, rel, 100,
                new com.ram.trading.signal.engine.service.replay.DailyBars.Stats(prevClose, 1e6, 1e8));
    }

    @Test
    void selectKeepsThePoolAndItsFiltersBiggestFirst() {
        List<OpeningScreen.Ranked> all = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            all.add(ranked("X" + i, i, 0.5, 100));
        }
        all.set(510, ranked("IN_3", 510, 3.0, 100));            // in pool
        all.set(520, ranked("IN_5", 520, 5.0, 100));            // in pool, bigger
        all.set(530, ranked("TOO_BIG", 530, 9.0, 100));         // above max-move
        all.set(540, ranked("GAPPED", 540, 3.0, 95));           // gap +5.3% from 95
        all.set(450, ranked("OUT_RANK", 450, 6.0, 100));        // outside the pool
        OpeningScreen.Ranking ranking = new OpeningScreen.Ranking(0.2, all);
        FadePaperBookService.Pool pool = new FadePaperBookService.Pool(500, 1000, 2, 8, 0, 0, -100, 2);

        OpeningScreen.Screen screen = FadePaperBookService.select(ranking, pool, r -> 1.0);

        assertEquals(List.of("IN_5", "IN_3"), screen.fade().stream().map(OpeningScreen.Ranked::symbol).toList());
        assertEquals("ranks 501-1000 up >= 2% < 8% gap < 2%", pool.label());
        assertEquals(null, FadePaperBookService.select(new OpeningScreen.Ranking(0.2, all.subList(0, 700)), pool, r -> 1.0));
    }

    @Test
    void spikeFilterIsAskedOnlyWhenSet() {
        List<OpeningScreen.Ranked> all = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            all.add(ranked("X" + i, i, i == 510 || i == 520 ? 3.0 : 0.5, 100));
        }
        OpeningScreen.Ranking ranking = new OpeningScreen.Ranking(0.2, all);
        FadePaperBookService.Pool spiky = new FadePaperBookService.Pool(500, 600, 2, 0, 1.5, 0, -100, 100);

        OpeningScreen.Screen screen = FadePaperBookService.select(ranking, spiky, r -> r.symbol().equals("X510") ? 2.0 : 1.0);

        assertEquals(List.of("X510"), screen.fade().stream().map(OpeningScreen.Ranked::symbol).toList());
        assertEquals(2, FadePaperBookService.select(ranking, FadePaperBookService.Pool.ROUND3,
                r -> { throw new AssertionError("spike not needed"); }).fade().size());
    }

    @Test
    void everyCandidateAndNoStopWhenSetToZero() {
        FadePaperBookService.Settings s = new FadePaperBookService.Settings(0, 40_000, 0, 0, 0, false, 0.03);
        List<FadePaperTrade> trades = FadePaperBookService.open(DAY, screen(0.4, 7), s, charges);

        assertEquals(7, trades.size());
        assertTrue(trades.stream().allMatch(t -> t.getStopLoss() == null));
        runDay(trades, 0.02, s);   // +6.4% squeeze by 15:00: no stop, so held to the exit
        assertTrue(trades.stream().allMatch(t -> "TIME".equals(t.getExitReason())));
        assertEquals("allx40000 stop none day none", s.label());
    }
}
