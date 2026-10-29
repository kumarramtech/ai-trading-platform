package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.StrategyReplayService.Result;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 5-minute opening-range breakout in "stocks in play" (after Zarattini, Barbon and
 * Aziz, 2024): rank the day's stocks by first-5-minute volume against their normal
 * volume, take the top N, trade in the direction of the first 5-minute candle with
 * a stop order at its high (long) or low (short), stop = a fraction of the 14-day
 * ATR, no target, out at the square-off.
 *
 * Normal volume is approximated by the 20-day average DAILY volume (the paper uses
 * the 14-day average of the first 5 minutes): the ranking is the same idea, the
 * threshold is not. 5% of a day's volume in 5 minutes is about 1x a normal open.
 *
 * Every variant is labelled INPLAY_* with gate RESEARCH so it never mixes into the
 * LIVE_RULES numbers; ALL5 is the same trade in every liquid stock (the control).
 */
final class InPlayOrb {

    static final String GATE = "RESEARCH";
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime RANGE_END = LocalTime.of(9, 20);
    private static final LocalTime LAST_ENTRY = LocalTime.of(14, 30);

    record Variant(String name, int top, double minRvol, double atrStopFraction) {
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant("INPLAY_ORB5 top20 stop 10% ATR", 20, 0.05, 0.10),
            new Variant("INPLAY_ORB5 top20 stop 20% ATR", 20, 0.05, 0.20),
            new Variant("INPLAY_ORB5 top10 stop 10% ATR", 10, 0.05, 0.10),
            new Variant("INPLAY_ORB5 ALL5 stop 10% ATR (control)", Integer.MAX_VALUE, 0.0, 0.10));

    private record Candidate(String symbol, List<MinuteCandle> series, double rvol, boolean buy,
                             double level, double atr) {
    }

    private InPlayOrb() {
    }

    static List<Result> replayDay(LocalDate day, Map<String, List<MinuteCandle>> daySeries, DailyBars daily,
                                  double minPrice, double minTurnoverCrore, double riskPerTrade, double maxNotional,
                                  double slippagePct, LocalTime squareOff, IntradayChargesCalculator charges) {
        List<Candidate> candidates = new ArrayList<>();
        daySeries.forEach((symbol, series) -> {
            DailyBars.Stats stats = daily.statsBefore(symbol, day);
            if (stats == null || Double.isNaN(stats.atr14()) || stats.atr14() <= 0 || stats.avgVolume20() <= 0
                    || stats.avgTurnover20() < minTurnoverCrore * 1e7) {
                return;
            }
            List<MinuteCandle> first = series.stream()
                    .filter(c -> !c.getMinute().toLocalTime().isBefore(OPEN)
                            && c.getMinute().toLocalTime().isBefore(RANGE_END))
                    .toList();
            if (first.size() < 5) {
                return;
            }
            double open = first.get(0).getOpen();
            double close = first.get(first.size() - 1).getClose();
            if (open < minPrice || close == open) {
                return;
            }
            double high = first.stream().mapToDouble(MinuteCandle::getHigh).max().orElse(open);
            double low = first.stream().mapToDouble(MinuteCandle::getLow).min().orElse(open);
            long volume = first.stream().mapToLong(c -> c.getVolume() == null ? 0 : c.getVolume()).sum();
            boolean buy = close > open;
            candidates.add(new Candidate(symbol, series, volume / stats.avgVolume20(), buy, buy ? high : low,
                    stats.atr14()));
        });
        candidates.sort(Comparator.comparingDouble(Candidate::rvol).reversed());

        List<Result> results = new ArrayList<>();
        for (Variant v : VARIANTS) {
            candidates.stream().filter(c -> c.rvol() >= v.minRvol()).limit(v.top()).forEach(c -> {
                Result r = trade(day, c, v, riskPerTrade, maxNotional, slippagePct, squareOff, charges);
                if (r != null) {
                    results.add(r);
                }
            });
        }
        return results;
    }

    /** Stop order at the range edge from 09:20; stop and square-off exits on 1-minute candles. */
    private static Result trade(LocalDate day, Candidate c, Variant v, double riskPerTrade, double maxNotional,
                                double slippagePct, LocalTime squareOff, IntradayChargesCalculator charges) {
        boolean buy = c.buy();
        List<MinuteCandle> series = c.series();
        int i = 0;
        while (i < series.size() && series.get(i).getMinute().toLocalTime().isBefore(RANGE_END)) {
            i++;
        }
        Double fill = null;
        LocalTime entryTime = null;
        for (; i < series.size(); i++) {
            MinuteCandle m = series.get(i);
            LocalTime t = m.getMinute().toLocalTime();
            if (t.isAfter(LAST_ENTRY)) {
                return null;
            }
            if (buy ? m.getHigh() >= c.level() : m.getLow() <= c.level()) {
                // A gap through the level fills at the open, not at the level.
                fill = buy ? Math.max(m.getOpen(), c.level()) : Math.min(m.getOpen(), c.level());
                entryTime = t;
                break;
            }
        }
        if (fill == null) {
            return null;
        }
        double entry = buy ? fill * (1 + slippagePct / 100) : fill * (1 - slippagePct / 100);
        double riskPerShare = v.atrStopFraction() * c.atr();
        double stop = buy ? entry - riskPerShare : entry + riskPerShare;
        int quantity = (int) Math.min(Math.floor(riskPerTrade / riskPerShare), Math.floor(maxNotional / entry));
        if (quantity < 1) {
            return null;
        }

        // The entry candle counts too: its range after the fill is unknown, so assume the worst.
        String exit = "SQUARE_OFF";
        double exitRef = series.get(i).getClose();
        LocalTime exitTime = squareOff;
        for (int k = i; k < series.size(); k++) {
            MinuteCandle m = series.get(k);
            LocalTime t = m.getMinute().toLocalTime();
            if (!t.isBefore(squareOff)) {
                break;
            }
            exitRef = m.getClose();
            exitTime = t;
            if (buy ? m.getLow() <= stop : m.getHigh() >= stop) {
                exit = "STOP";
                // A gap through the stop fills at the open.
                exitRef = k > i && (buy ? m.getOpen() < stop : m.getOpen() > stop) ? m.getOpen() : stop;
                break;
            }
        }
        double exitPrice = buy ? exitRef * (1 - slippagePct / 100) : exitRef * (1 + slippagePct / 100);
        double gross = (buy ? exitPrice - entry : entry - exitPrice) * quantity;
        String direction = buy ? "BUY" : "SELL";
        double cost = charges.roundTrip(direction, entry, exitPrice, quantity).getTotal();
        return new Result(day, entryTime, c.symbol(), v.name(), direction, "UNKNOWN", "N/A", GATE, exit, exitTime,
                entry, stop, Double.NaN, exitPrice, quantity, gross, cost, (gross - cost) / riskPerTrade,
                Double.NaN, Double.NaN, c.rvol(), Double.NaN, null, null, null);
    }
}
