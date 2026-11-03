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
 * stocks ranked by 20-session turnover, each with its move from the 09:15 open less
 * NIFTY's. One implementation for the replay and the live book, so paper trades the
 * exact picks the replay measured.
 */
public final class OpeningScreen {

    public static final int FADE_POOL_FROM = 500;
    public static final int FADE_POOL_SIZE = 100;
    public static final int BOUNCE_POOL = 500;
    public static final double FADE_MIN_MOVE = 2.0;
    public static final LocalTime ENTRY = LocalTime.of(9, 45);
    private static final LocalTime OPEN = LocalTime.of(9, 15);

    /** rank = 0-based turnover rank among the liquid stocks; rel = % move from the open less NIFTY's, at 09:45. */
    public record Ranked(String symbol, List<MinuteCandle> series, int rank, double rel, double price) {
    }

    /** fade: ranks 501-600 up >= 2% vs NIFTY, biggest first; bounce: ranks 1-500, weakest first. */
    public record Screen(double niftyMove, int liquid, List<Ranked> fade, List<Ranked> bounce) {
    }

    private OpeningScreen() {
    }

    /**
     * Null when NIFTY has no 09:45 candle or fewer than 550 stocks are liquid (the fade
     * pool would not exist). Series must start at 09:15 or later and be sorted.
     */
    public static Screen screen(LocalDate day, Map<String, List<MinuteCandle>> daySeries, List<MinuteCandle> nifty,
                                DailyBars daily, double minPrice, double minTurnoverCrore) {
        if (nifty == null || nifty.isEmpty()) {
            return null;
        }
        Double nifty945 = IntradaySignalStudy.closeAt(nifty, ENTRY);
        if (nifty945 == null) {
            return null;
        }
        double niftyMove = (nifty945 - nifty.get(0).getOpen()) / nifty.get(0).getOpen() * 100;

        record Liquid(String symbol, List<MinuteCandle> series, double turnover, double rel, double price) {
        }
        List<Liquid> liquid = new ArrayList<>();
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
                liquid.add(new Liquid(symbol, series, stats.avgTurnover20(), (p - open) / open * 100 - niftyMove, p));
            }
        });
        if (liquid.size() < FADE_POOL_FROM + FADE_POOL_SIZE / 2) {
            return null;
        }
        liquid.sort(Comparator.comparingDouble(Liquid::turnover).reversed());
        List<Ranked> ranked = new ArrayList<>(liquid.size());
        for (int i = 0; i < liquid.size(); i++) {
            Liquid l = liquid.get(i);
            ranked.add(new Ranked(l.symbol(), l.series(), i, l.rel(), l.price()));
        }
        List<Ranked> fade = ranked.subList(FADE_POOL_FROM, Math.min(ranked.size(), FADE_POOL_FROM + FADE_POOL_SIZE))
                .stream().filter(r -> r.rel() >= FADE_MIN_MOVE)
                .sorted(Comparator.comparingDouble(Ranked::rel).reversed()).toList();
        List<Ranked> bounce = ranked.subList(0, BOUNCE_POOL).stream()
                .sorted(Comparator.comparingDouble(Ranked::rel)).toList();
        return new Screen(niftyMove, liquid.size(), fade, bounce);
    }
}
