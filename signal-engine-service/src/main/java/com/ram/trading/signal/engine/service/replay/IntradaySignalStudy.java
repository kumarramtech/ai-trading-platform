package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Entry study: does an entry rule carry information before charges? Every rule
 * below is fixed in advance, holds to a set exit time with no stop or target (so
 * the exit rule cannot hide or fake an edge), and trades Rs 50,000 notional
 * (Rs 10k margin x 5x). Measured in basis points of notional:
 *
 *   raw    the price move from entry to exit, no costs
 *   gross  after 0.03% slippage each way (what "before charges" means)
 *   net    after real intraday charges too
 *
 * The t-stat is on daily averages (trades on one day move together), and the two
 * halves of the period are shown apart: a rule counts as a candidate only when it
 * is positive gross in BOTH halves with t >= 2. "opposite" is the same rule traded
 * the other way, so a strong negative rule shows up as a reversal candidate.
 *
 * Rules (decision on candles up to the decision minute only; liquid stocks only):
 *   REL_STRENGTH hh:mm   the 10 strongest (long) / weakest (short) stocks vs NIFTY since the open
 *   GAP fade             gap >= 3% / 1-3%, traded against the gap at 09:30
 *   PREV_DAY             yesterday's close-to-close move >= 5%, traded against it at 09:30
 *   OPEN_DRIVE           first 15 minutes >= 1% on >= 20% of a normal day's volume, with it at 09:30
 *   VWAP_TREND 10:30     above VWAP every minute since 09:45, >= 1% stronger than NIFTY, NIFTY up
 *   20D_BREAKOUT         first close above the 20-day high (below the 20-day low) 09:30-13:00
 *   LATE_MARKET 14:30    NIFTY >= 0.5% from its open: the 10 most traded stocks with it to 15:20
 *   ALL LONG             every liquid stock long 09:30-15:00 (the market's drift, the baseline)
 */
final class IntradaySignalStudy {

    static final double NOTIONAL = 50_000;
    static final int TOP = 10;
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime LATE_EXIT = LocalTime.of(15, 20);
    // A decision price older than this (an illiquid minute) is not traded.
    private static final int MAX_STALE_MINUTES = 3;

    record Trade(LocalDate day, String rule, boolean buy, double rawBps, double grossBps, double netBps) {
    }

    private record Stock(String symbol, List<MinuteCandle> series, DailyBars.Stats stats, List<DailyBars.Bar> past) {
        double open() {
            return series.get(0).getOpen();
        }
    }

    private IntradaySignalStudy() {
    }

    static List<Trade> replayDay(LocalDate day, Map<String, List<MinuteCandle>> daySeries, List<MinuteCandle> nifty,
                                 DailyBars daily, double minPrice, double minTurnoverCrore, double slippagePct,
                                 LocalTime squareOff, IntradayChargesCalculator charges) {
        List<Stock> stocks = new ArrayList<>();
        daySeries.forEach((symbol, series) -> {
            DailyBars.Stats stats = daily.statsBefore(symbol, day);
            if (stats == null || stats.avgTurnover20() < minTurnoverCrore * 1e7 || series.isEmpty()
                    || series.get(0).getOpen() < minPrice || series.get(0).getMinute().toLocalTime().isAfter(OPEN.plusMinutes(5))) {
                return;
            }
            stocks.add(new Stock(symbol, series, stats, daily.barsBefore(symbol, day, 20)));
        });
        if (stocks.isEmpty() || nifty == null || nifty.isEmpty()) {
            return List.of();
        }
        Study s = new Study(day, nifty, slippagePct, charges);

        relativeStrength(s, stocks, LocalTime.of(9, 45), squareOff);
        relativeStrength(s, stocks, LocalTime.of(11, 0), squareOff);
        relativeStrength(s, stocks, LocalTime.of(14, 30), LATE_EXIT);

        LocalTime t930 = LocalTime.of(9, 30);
        for (Stock st : stocks) {
            Double p = s.close(st, t930);
            if (p == null) {
                continue;
            }
            double prev = st.stats().prevClose();
            double gap = prev > 0 ? (st.open() - prev) / prev * 100 : 0;
            if (Math.abs(gap) >= 3) {
                s.trade("GAP fade >= 3%", st, gap < 0, t930, squareOff);
            } else if (Math.abs(gap) >= 1) {
                s.trade("GAP fade 1-3%", st, gap < 0, t930, squareOff);
            }

            List<DailyBars.Bar> past = st.past();
            if (past.size() >= 2) {
                double before = past.get(past.size() - 2).close();
                double yesterday = (prev - before) / before * 100;
                if (Math.abs(yesterday) >= 5) {
                    s.trade("PREV_DAY move >= 5% fade", st, yesterday < 0, t930, squareOff);
                }
            }

            double drive = (p - st.open()) / st.open() * 100;
            double volume = volumeUntil(st.series(), t930);
            if (Math.abs(drive) >= 1 && st.stats().avgVolume20() > 0 && volume / st.stats().avgVolume20() >= 0.20) {
                s.trade("OPEN_DRIVE >= 1% on 20% volume", st, drive > 0, t930, squareOff);
            }

            s.trade("ALL LONG 09:30 (baseline)", st, true, t930, squareOff);
        }

        vwapTrend(s, stocks, squareOff);
        breakout(s, stocks, squareOff);
        lateMarket(s, stocks);
        return s.trades;
    }

    private static void relativeStrength(Study s, List<Stock> stocks, LocalTime at, LocalTime exit) {
        Double niftyMove = s.niftyMove(at);
        if (niftyMove == null) {
            return;
        }
        List<Map.Entry<Stock, Double>> ranked = new ArrayList<>();
        for (Stock st : stocks) {
            Double p = s.close(st, at);
            if (p != null) {
                ranked.add(Map.entry(st, (p - st.open()) / st.open() * 100 - niftyMove));
            }
        }
        if (ranked.size() < 4 * TOP) {
            return;
        }
        ranked.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        String name = "REL_STRENGTH " + at + " -> " + exit;
        ranked.subList(0, TOP).forEach(e -> s.trade(name + " top10 LONG", e.getKey(), true, at, exit));
        ranked.subList(ranked.size() - TOP, ranked.size())
                .forEach(e -> s.trade(name + " bottom10 SHORT", e.getKey(), false, at, exit));
    }

    private static void vwapTrend(Study s, List<Stock> stocks, LocalTime exit) {
        LocalTime from = LocalTime.of(9, 45);
        LocalTime at = LocalTime.of(10, 30);
        Double niftyMove = s.niftyMove(at);
        if (niftyMove == null || niftyMove == 0) {
            return;
        }
        boolean up = niftyMove > 0;
        for (Stock st : stocks) {
            Double p = s.close(st, at);
            if (p == null) {
                continue;
            }
            double rel = (p - st.open()) / st.open() * 100 - niftyMove;
            if (up ? rel < 1 : rel > -1) {
                continue;
            }
            double pv = 0;
            double v = 0;
            boolean held = true;
            for (MinuteCandle c : st.series()) {
                LocalTime t = c.getMinute().toLocalTime();
                if (t.isAfter(at)) {
                    break;
                }
                long vol = c.getVolume() == null ? 0 : c.getVolume();
                pv += (c.getHigh() + c.getLow() + c.getClose()) / 3 * vol;
                v += vol;
                if (!t.isBefore(from) && v > 0 && (up ? c.getClose() <= pv / v : c.getClose() >= pv / v)) {
                    held = false;
                    break;
                }
            }
            if (held && v > 0) {
                s.trade("VWAP_TREND 10:30 with NIFTY", st, up, at, exit);
            }
        }
    }

    private static void breakout(Study s, List<Stock> stocks, LocalTime exit) {
        LocalTime start = LocalTime.of(9, 30);
        LocalTime end = LocalTime.of(13, 0);
        for (Stock st : stocks) {
            if (st.past().size() < 20) {
                continue;
            }
            double high = st.past().stream().mapToDouble(DailyBars.Bar::high).max().orElse(Double.MAX_VALUE);
            double low = st.past().stream().mapToDouble(DailyBars.Bar::low).min().orElse(0);
            // A gap beyond the level is not a breakout during the session.
            if (st.open() > high || st.open() < low) {
                continue;
            }
            for (MinuteCandle c : st.series()) {
                LocalTime t = c.getMinute().toLocalTime();
                if (t.isBefore(start)) {
                    continue;
                }
                if (t.isAfter(end)) {
                    break;
                }
                if (c.getClose() > high || c.getClose() < low) {
                    s.trade("20D_BREAKOUT 09:30-13:00", st, c.getClose() > high, t, exit);
                    break;
                }
            }
        }
    }

    private static void lateMarket(Study s, List<Stock> stocks) {
        LocalTime at = LocalTime.of(14, 30);
        Double niftyMove = s.niftyMove(at);
        if (niftyMove == null || Math.abs(niftyMove) < 0.5) {
            return;
        }
        stocks.stream()
                .sorted(Comparator.comparingDouble((Stock st) -> st.stats().avgTurnover20()).reversed())
                .limit(TOP)
                .forEach(st -> s.trade("LATE_MARKET 14:30 -> 15:20, NIFTY >= 0.5%", st, niftyMove > 0, at, LATE_EXIT));
    }

    private static double volumeUntil(List<MinuteCandle> series, LocalTime t) {
        double v = 0;
        for (MinuteCandle c : series) {
            if (!c.getMinute().toLocalTime().isBefore(t)) {
                break;
            }
            v += c.getVolume() == null ? 0 : c.getVolume();
        }
        return v;
    }

    /** One day's prices and the trades taken on it. */
    private static final class Study {
        final LocalDate day;
        final List<MinuteCandle> nifty;
        final double slippagePct;
        final IntradayChargesCalculator charges;
        final List<Trade> trades = new ArrayList<>();

        Study(LocalDate day, List<MinuteCandle> nifty, double slippagePct,
              IntradayChargesCalculator charges) {
            this.day = day;
            this.nifty = nifty;
            this.slippagePct = slippagePct;
            this.charges = charges;
        }

        Double close(Stock st, LocalTime t) {
            return closeAt(st.series(), t);
        }

        Double niftyMove(LocalTime t) {
            Double p = closeAt(nifty, t);
            return p == null ? null : (p - nifty.get(0).getOpen()) / nifty.get(0).getOpen() * 100;
        }

        void trade(String rule, Stock st, boolean buy, LocalTime at, LocalTime exitAt) {
            Double in = closeAt(st.series(), at);
            Double out = closeAt(st.series(), exitAt.minusMinutes(1));
            if (in == null || out == null) {
                return;
            }
            double raw = (out - in) / in * 1e4 * (buy ? 1 : -1);
            double entry = buy ? in * (1 + slippagePct / 100) : in * (1 - slippagePct / 100);
            double exit = buy ? out * (1 - slippagePct / 100) : out * (1 + slippagePct / 100);
            int quantity = (int) Math.floor(NOTIONAL / entry);
            if (quantity < 1) {
                return;
            }
            double value = entry * quantity;
            double gross = (buy ? exit - entry : entry - exit) * quantity;
            double cost = charges.roundTrip(buy ? "BUY" : "SELL", entry, exit, quantity).getTotal();
            trades.add(new Trade(day, rule, buy, raw, gross / value * 1e4, (gross - cost) / value * 1e4));
        }
    }

    /** Close of the last candle at or before {@code t}; null when there is none within a few minutes. */
    static Double closeAt(List<MinuteCandle> series, LocalTime t) {
        MinuteCandle last = null;
        for (MinuteCandle c : series) {
            if (c.getMinute().toLocalTime().isAfter(t)) {
                break;
            }
            last = c;
        }
        if (last == null || last.getMinute().toLocalTime().plusMinutes(MAX_STALE_MINUTES).isBefore(t)) {
            return null;
        }
        return last.getClose();
    }

    // -------------------------------------------------------------- report

    static String render(List<Trade> trades, double slippagePct, IntradayChargesCalculator charges) {
        TreeSet<LocalDate> days = new TreeSet<>();
        trades.forEach(t -> days.add(t.day()));
        if (days.isEmpty()) {
            return "ENTRY STUDY: no trades\n";
        }
        LocalDate split = new ArrayList<>(days).get(days.size() / 2);

        Map<String, List<Trade>> byRule = new LinkedHashMap<>();
        trades.forEach(t -> byRule.computeIfAbsent(t.rule(), k -> new ArrayList<>()).add(t));

        StringBuilder out = new StringBuilder();
        out.append("ENTRY STUDY: fixed rules held to the exit time, no stop, Rs 50k notional (bps of notional)\n")
                .append("halves: ").append(days.first()).append(" .. ").append(split.minusDays(1))
                .append(" | ").append(split).append(" .. ").append(days.last()).append(", ")
                .append(days.size()).append(" sessions\n")
                .append("gross = after 0.03% slippage each way, before charges; t = daily-average t-stat on gross\n")
                .append("opposite = the same rule traded the other way (gross)\n\n");
        out.append(String.format("%-58s %6s %5s %7s %7s %7s %6s %6s %7s %7s %8s %8s  %s%n",
                "rule", "n", "/day", "raw", "gross", "net", "win%", "t", "H1 gr", "H2 gr", "opp gr", "Rs/day", "verdict"));

        double roundTripSlipBps = 2 * slippagePct * 100;
        for (Map.Entry<String, List<Trade>> e : byRule.entrySet()) {
            List<Trade> list = e.getValue();
            double raw = avg(list, Trade::rawBps);
            double gross = avg(list, Trade::grossBps);
            double net = avg(list, Trade::netBps);
            double win = list.stream().filter(t -> t.netBps() > 0).count() * 100.0 / list.size();
            double t = dailyT(list, Trade::grossBps);
            List<Trade> h1 = list.stream().filter(x -> x.day().isBefore(split)).toList();
            List<Trade> h2 = list.stream().filter(x -> !x.day().isBefore(split)).toList();
            double g1 = h1.isEmpty() ? Double.NaN : avg(h1, Trade::grossBps);
            double g2 = h2.isEmpty() ? Double.NaN : avg(h2, Trade::grossBps);
            double opposite = -raw - roundTripSlipBps;
            double opp1 = h1.isEmpty() ? Double.NaN : -avg(h1, Trade::rawBps) - roundTripSlipBps;
            double opp2 = h2.isEmpty() ? Double.NaN : -avg(h2, Trade::rawBps) - roundTripSlipBps;
            long tradeDays = list.stream().map(Trade::day).distinct().count();
            double perDay = list.size() / (double) days.size();
            double rupeesPerDay = net / 1e4 * NOTIONAL * perDay;

            String verdict = g1 > 0 && g2 > 0 && t >= 2 ? "CANDIDATE"
                    : opp1 > 0 && opp2 > 0 && t <= -2 ? "CANDIDATE (opposite)"
                    : "-";
            out.append(String.format("%-58s %6d %5.1f %7.1f %7.1f %7.1f %6.1f %6.2f %7.1f %7.1f %8.1f %8.0f  %s%n",
                    e.getKey(), list.size(), list.size() / (double) Math.max(1, tradeDays), raw, gross, net, win, t,
                    g1, g2, opposite, rupeesPerDay, verdict));
        }
        out.append("\n/day = trades per day the rule fired; Rs/day = net per session over all sessions.\n")
                .append("Net needs gross above ~").append(String.format("%.0f", costBps(charges)))
                .append(" bps (Rs 20 x 2 brokerage, STT, exchange, GST, stamp on Rs 50k).\n");
        return out.toString();
    }

    private static double costBps(IntradayChargesCalculator charges) {
        IntradayChargesCalculator.Charges c = charges.roundTrip("BUY", 100, 100, 500);
        return c.getTotal() / NOTIONAL * 1e4;
    }

    private static double avg(List<Trade> list, java.util.function.ToDoubleFunction<Trade> f) {
        return list.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    private static double dailyT(List<Trade> list, java.util.function.ToDoubleFunction<Trade> f) {
        Map<LocalDate, double[]> byDay = new TreeMap<>();
        list.forEach(t -> {
            double[] a = byDay.computeIfAbsent(t.day(), d -> new double[2]);
            a[0] += f.applyAsDouble(t);
            a[1]++;
        });
        double[] means = byDay.values().stream().mapToDouble(a -> a[0] / a[1]).toArray();
        if (means.length < 3) {
            return Double.NaN;
        }
        double mean = java.util.Arrays.stream(means).average().orElse(0);
        double var = java.util.Arrays.stream(means).map(m -> (m - mean) * (m - mean)).sum() / (means.length - 1);
        return var <= 0 ? Double.NaN : mean / Math.sqrt(var / means.length);
    }
}
