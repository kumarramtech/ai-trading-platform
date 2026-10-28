package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The live index regime rebuilt from NIFTY and BANKNIFTY 1-minute closes,
 * with market-stream's MarketRegimeTracker rule: each index is UP/DOWN once its
 * move from the day open passes threshold + buffer and stays so until it falls
 * back inside threshold - buffer; BULLISH/BEARISH when both agree, otherwise
 * SIDEWAYS; a published regime is held at least minHold before it changes.
 */
final class RegimeTimeline {

    record Point(Trend trend, LocalTime since) {
    }

    static final double THRESHOLD_PCT = 0.15;
    static final double BUFFER_PCT = 0.05;
    static final Duration MIN_HOLD = Duration.ofMinutes(5);

    private enum Direction { UP, DOWN, FLAT }

    private final TreeMap<LocalTime, Point> points;

    private RegimeTimeline(TreeMap<LocalTime, Point> points) {
        this.points = points;
    }

    /** The regime in force when the candle at {@code t} completed; null before the first index candle. */
    Point at(LocalTime t) {
        Map.Entry<LocalTime, Point> e = points.floorEntry(t);
        return e == null ? null : e.getValue();
    }

    static RegimeTimeline build(List<MinuteCandle> nifty, List<MinuteCandle> bank) {
        TreeMap<LocalTime, Double> niftyClose = closes(nifty);
        TreeMap<LocalTime, Double> bankClose = closes(bank);
        double niftyOpen = nifty.get(0).getOpen();
        double bankOpen = bank.get(0).getOpen();

        Direction n = Direction.FLAT;
        Direction b = Direction.FLAT;
        Trend published = null;
        LocalTime publishedAt = null;
        TreeMap<LocalTime, Point> points = new TreeMap<>();

        for (LocalTime t : niftyClose.keySet()) {
            Map.Entry<LocalTime, Double> bc = bankClose.floorEntry(t);
            if (bc == null) {
                continue;
            }
            n = next(n, (niftyClose.get(t) - niftyOpen) / niftyOpen * 100.0);
            b = next(b, (bc.getValue() - bankOpen) / bankOpen * 100.0);

            Trend candidate = n == Direction.UP && b == Direction.UP ? Trend.BULLISH
                    : n == Direction.DOWN && b == Direction.DOWN ? Trend.BEARISH
                    : Trend.SIDEWAYS;

            if (published == null
                    || (candidate != published && !Duration.between(publishedAt, t).minus(MIN_HOLD).isNegative())) {
                published = candidate;
                publishedAt = t;
            }
            points.put(t, new Point(published, publishedAt));
        }
        return new RegimeTimeline(points);
    }

    private static Direction next(Direction current, double changePct) {
        double enter = THRESHOLD_PCT + BUFFER_PCT;
        double exit = Math.max(0.0, THRESHOLD_PCT - BUFFER_PCT);
        if (changePct >= enter) return Direction.UP;
        if (changePct <= -enter) return Direction.DOWN;
        if (current == Direction.UP && changePct > exit) return Direction.UP;
        if (current == Direction.DOWN && changePct < -exit) return Direction.DOWN;
        return Direction.FLAT;
    }

    private static TreeMap<LocalTime, Double> closes(List<MinuteCandle> candles) {
        TreeMap<LocalTime, Double> map = new TreeMap<>();
        candles.forEach(c -> map.put(c.getMinute().toLocalTime(), c.getClose()));
        return map;
    }
}
