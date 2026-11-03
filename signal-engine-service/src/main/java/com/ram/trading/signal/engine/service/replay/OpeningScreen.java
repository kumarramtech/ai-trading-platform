package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The 09:45 screen behind the Round 3 risk books and the live FADE paper book: liquid
 * stocks ranked by 20-session turnover (point in time: only sessions before the day),
 * each with its move from the 09:15 open less NIFTY's. One implementation for the
 * replay and the live book, so paper trades the exact picks the replay measured.
 */
public final class OpeningScreen {

    public static final int FADE_POOL_FROM = 500;
    public static final int FADE_POOL_SIZE = 100;
    public static final int BOUNCE_POOL = 500;
    public static final double FADE_MIN_MOVE = 2.0;
    public static final LocalTime ENTRY = LocalTime.of(9, 45);
    private static final LocalTime OPEN = LocalTime.of(9, 15);

    /** rank = 0-based turnover rank among the liquid stocks; rel = % move from the open less NIFTY's, at 09:45. */
    public record Ranked(String symbol, List<MinuteCandle> series, int rank, double rel, double price,
                         DailyBars.Stats stats) {

        public Ranked(String symbol, List<MinuteCandle> series, int rank, double rel, double price) {
            this(symbol, series, rank, rel, price, null);
        }

        public double open() {
            return series.get(0).getOpen();
        }
    }

    /** fade: the pool's stocks up >= the minimum move vs NIFTY, biggest first; bounce: ranks 1-500, weakest first. */
    public record Screen(double niftyMove, int liquid, List<Ranked> fade, List<Ranked> bounce) {
    }

    /** Every liquid stock, most traded first. */
    public record Ranking(double niftyMove, List<Ranked> ranked) {
    }

    private OpeningScreen() {
    }

    /** The Round 3 screen: ranks 501-600, up >= 2%. */
    public static Screen screen(LocalDate day, Map<String, List<MinuteCandle>> daySeries, List<MinuteCandle> nifty,
                                DailyBars daily, double minPrice, double minTurnoverCrore) {
        return screen(day, daySeries, nifty, daily, minPrice, minTurnoverCrore, FADE_POOL_FROM, FADE_POOL_SIZE,
                FADE_MIN_MOVE);
    }

    /**
     * Null when NIFTY has no 09:45 candle or the liquid stocks do not reach half the pool.
     * {@code poolFrom} is the 0-based first rank (500 = rank 501).
     */
    public static Screen screen(LocalDate day, Map<String, List<MinuteCandle>> daySeries, List<MinuteCandle> nifty,
                                DailyBars daily, double minPrice, double minTurnoverCrore,
                                int poolFrom, int poolSize, double minMove) {
        Ranking ranking = rank(day, daySeries, nifty, daily, minPrice, minTurnoverCrore);
        if (ranking == null || ranking.ranked().size() < poolFrom + poolSize / 2) {
            return null;
        }
        List<Ranked> ranked = ranking.ranked();
        List<Ranked> fade = ranked.subList(poolFrom, Math.min(ranked.size(), poolFrom + poolSize))
                .stream().filter(r -> r.rel() >= minMove)
                .sorted(Comparator.comparingDouble(Ranked::rel).reversed()).toList();
        List<Ranked> bounce = ranked.subList(0, Math.min(ranked.size(), BOUNCE_POOL)).stream()
                .sorted(Comparator.comparingDouble(Ranked::rel)).toList();
        return new Screen(ranking.niftyMove(), ranked.size(), fade, bounce);
    }

    /** Gap of the 09:15 open from yesterday's close, %. */
    public static double gapPct(Ranked r) {
        double prev = r.stats() == null ? 0 : r.stats().prevClose();
        return prev > 0 ? (r.open() - prev) / prev * 100 : 0;
    }

    /** The last 5 sessions' average turnover over the 20-session average (attention rising or fading). */
    public static double turnoverSpike(DailyBars daily, Ranked r, LocalDate day) {
        double avg20 = r.stats() == null ? 0 : r.stats().avgTurnover20();
        double t5 = daily.barsBefore(r.symbol(), day, 5).stream().mapToDouble(b -> b.close() * b.volume())
                .average().orElse(0);
        return avg20 > 0 ? t5 / avg20 : 0;
    }

    /** Null when NIFTY has no 09:45 candle. Series must start at 09:15 or later and be sorted. */
    public static Ranking rank(LocalDate day, Map<String, List<MinuteCandle>> daySeries, List<MinuteCandle> nifty,
                               DailyBars daily, double minPrice, double minTurnoverCrore) {
        if (nifty == null || nifty.isEmpty()) {
            return null;
        }
        Double nifty945 = IntradaySignalStudy.closeAt(nifty, ENTRY);
        if (nifty945 == null) {
            return null;
        }
        double niftyMove = (nifty945 - nifty.get(0).getOpen()) / nifty.get(0).getOpen() * 100;

        List<Ranked> liquid = new ArrayList<>();
        daySeries.forEach((symbol, series) -> {
            DailyBars.Stats stats = daily.statsBefore(symbol, day);
            if (stats == null || stats.avgTurnover20() < minTurnoverCrore * 1e7 || series.isEmpty()
                    || series.get(0).getOpen() < minPrice
                    || series.get(0).getMinute().toLocalTime().isAfter(OPEN.plusMinutes(5))) {
                return;
            }
            Double p = IntradaySignalStudy.closeAt(series, ENTRY);
            if (p != null) {
                double open = series.get(0).getOpen();
                liquid.add(new Ranked(symbol, series, -1, (p - open) / open * 100 - niftyMove, p, stats));
            }
        });
        liquid.sort(Comparator.comparingDouble((Ranked r) -> r.stats().avgTurnover20()).reversed());
        List<Ranked> ranked = new ArrayList<>(liquid.size());
        for (int i = 0; i < liquid.size(); i++) {
            Ranked r = liquid.get(i);
            ranked.add(new Ranked(r.symbol(), r.series(), i, r.rel(), r.price(), r.stats()));
        }
        return new Ranking(niftyMove, ranked);
    }
}
