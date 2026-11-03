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
 * Round 3 (2026-11-03): the two Round 2 leads run as a whole Rs 1 lakh account, with the
 * loss limits a real account needs, minute by minute:
 *
 *   FADE    short the stocks up >= 2% vs NIFTY at 09:45 among turnover ranks 501-600
 *           (+44 bps gross, t 3.9 on that tier; loses on the top 500)
 *   BOUNCE  buy the weakest vs NIFTY at 09:45 among turnover ranks 1-500 (+30 bps, t 2.2)
 *
 * Each book: at most K positions per side entered at 09:45, Rs N each, a stop s% from entry
 * per position, and a DAILY STOP: once realized + open P&L (after slippage and charges)
 * reaches -D the whole book is closed and nothing more is traded that day. That bounds a
 * day's loss by construction; the replay measures how far gaps push past it. Books are
 * fixed before the run. Needs the replay universe to cover ranks 1-600 (universe >= 600).
 */
final class RiskBookStudy {

    static final double CAPITAL = 100_000;
    static final int FADE_POOL_FROM = 500;
    static final int FADE_POOL_SIZE = 100;
    static final int BOUNCE_POOL = 500;
    static final double FADE_MIN_MOVE = 2.0;
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime ENTRY = LocalTime.of(9, 45);

    /** One account set-up. {@code fadeCount}/{@code bounceCount} 0 = that side off; dailyStop/takeProfit 0 = none. */
    record Book(String name, int fadeCount, int bounceCount, double notional, double fadeStopPct,
                double bounceStopPct, double dailyStop, double takeProfit, boolean fadeOnlyNiftyUp, LocalTime exit) {
    }

    record DayResult(LocalDate day, String book, double netRs, int trades, boolean halted, boolean tookProfit) {
    }

    static final List<Book> BOOKS = List.of(
            new Book("FADE 3 x 50k, stop 2%, day stop 1500", 3, 0, 50_000, 2, 0, 1500, 0, false, LocalTime.of(15, 0)),
            new Book("FADE 5 x 40k, stop 2%, day stop 1500", 5, 0, 40_000, 2, 0, 1500, 0, false, LocalTime.of(15, 0)),
            new Book("FADE 5 x 40k, stop 2%, NO day stop", 5, 0, 40_000, 2, 0, 0, 0, false, LocalTime.of(15, 0)),
            new Book("FADE 5 x 40k, stop 2%, day stop 1500, NIFTY up", 5, 0, 40_000, 2, 0, 1500, 0, true, LocalTime.of(15, 0)),
            new Book("FADE 5 x 40k, stop 1.5%, day stop 1000", 5, 0, 40_000, 1.5, 0, 1000, 0, false, LocalTime.of(15, 0)),
            new Book("BOUNCE 5 x 40k, stop 3%, day stop 1500", 0, 5, 40_000, 0, 3, 1500, 0, false, LocalTime.of(15, 0)),
            new Book("BOTH 3+3 x 40k, day stop 1500", 3, 3, 40_000, 2, 3, 1500, 0, false, LocalTime.of(15, 0)),
            new Book("BOTH 3+3 x 40k, day stop 1000", 3, 3, 40_000, 2, 3, 1000, 0, false, LocalTime.of(15, 0)),
            new Book("BOTH 3+3 x 40k, NO day stop", 3, 3, 40_000, 2, 3, 0, 0, false, LocalTime.of(15, 0)),
            new Book("BOTH 3+3 x 40k, day stop 1500, take profit 2000", 3, 3, 40_000, 2, 3, 1500, 2000, false, LocalTime.of(15, 0)),
            new Book("BOTH 5+5 x 25k, day stop 1500", 5, 5, 25_000, 2, 3, 1500, 0, false, LocalTime.of(15, 0)),
            new Book("BOTH 3+3 x 40k, day stop 1500, exit 14:00", 3, 3, 40_000, 2, 3, 1500, 0, false, LocalTime.of(14, 0)));

    private RiskBookStudy() {
    }

    record Pick(String symbol, List<MinuteCandle> series, boolean buy, double stopPct) {
    }

    private static final class Position {
        final boolean buy;
        final double entry;
        final int qty;
        final double stop;
        final double charges;
        final TreeMap<LocalTime, MinuteCandle> byMinute = new TreeMap<>();
        double last;
        Double exit;

        Position(Pick p, double entryRef, double notional, double slippagePct, IntradayChargesCalculator calc) {
            this.buy = p.buy();
            this.entry = buy ? entryRef * (1 + slippagePct / 100) : entryRef * (1 - slippagePct / 100);
            this.qty = (int) Math.floor(notional / entry);
            this.stop = p.stopPct() <= 0 ? Double.NaN
                    : buy ? entry * (1 - p.stopPct() / 100) : entry * (1 + p.stopPct() / 100);
            // Charges at the entry price both ways: close enough for a stop decision, exact at the end.
            this.charges = qty < 1 ? 0 : calc.roundTrip(buy ? "BUY" : "SELL", entry, entry, qty).getTotal();
            this.last = entryRef;
            p.series().forEach(c -> byMinute.put(c.getMinute().toLocalTime(), c));
        }

        double pnlAt(double ref, double slippagePct) {
            double out = buy ? ref * (1 - slippagePct / 100) : ref * (1 + slippagePct / 100);
            return (buy ? out - entry : entry - out) * qty - charges;
        }
    }

    static List<DayResult> replayDay(LocalDate day, Map<String, List<MinuteCandle>> daySeries, List<MinuteCandle> nifty,
                                     DailyBars daily, double minPrice, double minTurnoverCrore, double slippagePct,
                                     IntradayChargesCalculator charges) {
        if (nifty == null || nifty.isEmpty()) {
            return List.of();
        }
        Double nifty945 = IntradaySignalStudy.closeAt(nifty, ENTRY);
        if (nifty945 == null) {
            return List.of();
        }
        double niftyMove = (nifty945 - nifty.get(0).getOpen()) / nifty.get(0).getOpen() * 100;

        // Liquid stocks by 20-session turnover, most traded first (same filter as the entry study).
        record Liquid(String symbol, List<MinuteCandle> series, double turnover, double rel) {
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
                liquid.add(new Liquid(symbol, series, stats.avgTurnover20(), (p - open) / open * 100 - niftyMove));
            }
        });
        if (liquid.size() < FADE_POOL_FROM + FADE_POOL_SIZE / 2) {
            return List.of();
        }
        liquid.sort(Comparator.comparingDouble(Liquid::turnover).reversed());
        List<Liquid> fadePool = liquid.subList(FADE_POOL_FROM, Math.min(liquid.size(), FADE_POOL_FROM + FADE_POOL_SIZE))
                .stream().filter(l -> l.rel() >= FADE_MIN_MOVE)
                .sorted(Comparator.comparingDouble(Liquid::rel).reversed()).toList();
        List<Liquid> bouncePool = liquid.subList(0, BOUNCE_POOL).stream()
                .sorted(Comparator.comparingDouble(Liquid::rel)).toList();

        List<DayResult> out = new ArrayList<>();
        for (Book book : BOOKS) {
            List<Pick> picks = new ArrayList<>();
            if (!book.fadeOnlyNiftyUp() || niftyMove > 0) {
                fadePool.stream().limit(book.fadeCount())
                        .forEach(l -> picks.add(new Pick(l.symbol(), l.series(), false, book.fadeStopPct())));
            }
            bouncePool.stream().limit(book.bounceCount())
                    .forEach(l -> picks.add(new Pick(l.symbol(), l.series(), true, book.bounceStopPct())));
            out.add(simulate(day, book, picks, slippagePct, charges));
        }
        return out;
    }

    /** Runs one book through the day minute by minute. */
    static DayResult simulate(LocalDate day, Book book, List<Pick> picks, double slippagePct,
                              IntradayChargesCalculator charges) {
        List<Position> open = new ArrayList<>();
        for (Pick p : picks) {
            Double ref = IntradaySignalStudy.closeAt(p.series(), ENTRY);
            if (ref == null) {
                continue;
            }
            Position pos = new Position(p, ref, book.notional(), slippagePct, charges);
            if (pos.qty >= 1) {
                open.add(pos);
            }
        }
        if (open.isEmpty()) {
            return new DayResult(day, book.name(), 0, 0, false, false);
        }

        double realized = 0;
        boolean halted = false;
        boolean tookProfit = false;
        LocalTime last = book.exit().minusMinutes(1);
        for (LocalTime t = ENTRY.plusMinutes(1); !t.isAfter(last) && !open.isEmpty(); t = t.plusMinutes(1)) {
            for (Position pos : new ArrayList<>(open)) {
                MinuteCandle c = pos.byMinute.get(t);
                if (c == null) {
                    continue;
                }
                if (!Double.isNaN(pos.stop) && (pos.buy ? c.getLow() <= pos.stop : c.getHigh() >= pos.stop)) {
                    // A gap through the stop fills at the open, not the stop.
                    double fill = pos.buy ? Math.min(pos.stop, c.getOpen()) : Math.max(pos.stop, c.getOpen());
                    realized += pos.pnlAt(fill, slippagePct);
                    open.remove(pos);
                    continue;
                }
                pos.last = c.getClose();
            }
            double bookPnl = realized;
            for (Position pos : open) {
                bookPnl += pos.pnlAt(pos.last, slippagePct);
            }
            boolean stop = book.dailyStop() > 0 && bookPnl <= -book.dailyStop();
            boolean profit = book.takeProfit() > 0 && bookPnl >= book.takeProfit();
            if (stop || profit) {
                realized = bookPnl;
                open.clear();
                halted = stop;
                tookProfit = profit;
            }
        }
        for (Position pos : open) {
            realized += pos.pnlAt(pos.last, slippagePct);
        }
        return new DayResult(day, book.name(), realized, picks.size(), halted, tookProfit);
    }

    // -------------------------------------------------------------- report

    static String render(List<DayResult> results) {
        if (results.isEmpty()) {
            return "ROUND 3 RISK BOOKS: no days (the universe must cover turnover ranks 1-600)\n";
        }
        TreeSet<LocalDate> days = new TreeSet<>();
        results.forEach(r -> days.add(r.day()));
        LocalDate split = new ArrayList<>(days).get(days.size() / 2);
        Map<String, List<DayResult>> byBook = new LinkedHashMap<>();
        results.forEach(r -> byBook.computeIfAbsent(r.book(), k -> new ArrayList<>()).add(r));

        StringBuilder out = new StringBuilder();
        out.append("ROUND 3 RISK BOOKS: Rs 1 lakh account, entries 09:45, net of slippage and charges (Rs per day)\n")
                .append("fade = short up >= 2% vs NIFTY, turnover ranks 501-600; bounce = buy weakest, ranks 1-500\n")
                .append("halves split at ").append(split).append(", ").append(days.size()).append(" sessions\n\n");
        out.append(String.format("%-50s %5s %8s %8s %8s %6s %6s %9s %9s %9s %6s %6s  %s%n",
                "book", "tr/d", "Rs/day", "H1", "H2", "t", "win%", "worstDay", "worst5d", "maxDD", "halt", "tp", "verdict"));
        for (Map.Entry<String, List<DayResult>> e : byBook.entrySet()) {
            List<DayResult> list = e.getValue();
            double[] daily = list.stream().mapToDouble(DayResult::netRs).toArray();
            double avg = mean(daily);
            double h1 = mean(list.stream().filter(r -> r.day().isBefore(split)).mapToDouble(DayResult::netRs).toArray());
            double h2 = mean(list.stream().filter(r -> !r.day().isBefore(split)).mapToDouble(DayResult::netRs).toArray());
            double t = tStat(daily);
            double win = list.stream().filter(r -> r.netRs() > 0).count() * 100.0 / list.size();
            double worst = java.util.Arrays.stream(daily).min().orElse(0);
            double worst5 = worstWindow(daily, 5);
            double dd = maxDrawdown(daily);
            long halts = list.stream().filter(DayResult::halted).count();
            long tps = list.stream().filter(DayResult::tookProfit).count();
            double perDay = list.stream().mapToInt(DayResult::trades).average().orElse(0);
            String verdict = avg >= 300 && h1 > 0 && h2 > 0 && t >= 2 && worst >= -3000 ? "PASS"
                    : avg > 0 && h1 > 0 && h2 > 0 ? "positive, below the bar"
                    : "-";
            out.append(String.format("%-50s %5.1f %8.0f %8.0f %8.0f %6.2f %6.1f %9.0f %9.0f %9.0f %6d %6d  %s%n",
                    e.getKey(), perDay, avg, h1, h2, t, win, worst, worst5, dd, halts, tps, verdict));
        }
        out.append("\nPASS = Rs/day >= 300, positive in both halves, t >= 2 on daily net, worst day >= -3000.\n")
                .append("worst5d = worst 5 consecutive sessions; halt = days the day stop closed the book; tp = take-profit days.\n")
                .append("Stops fill at the stop (or the open on a gap through it); the day stop closes at that minute's close.\n");
        return out.toString();
    }

    private static double mean(double[] a) {
        return a.length == 0 ? Double.NaN : java.util.Arrays.stream(a).average().orElse(0);
    }

    private static double tStat(double[] a) {
        if (a.length < 3) {
            return Double.NaN;
        }
        double m = mean(a);
        double var = java.util.Arrays.stream(a).map(x -> (x - m) * (x - m)).sum() / (a.length - 1);
        return var <= 0 ? Double.NaN : m / Math.sqrt(var / a.length);
    }

    static double worstWindow(double[] a, int n) {
        double worst = 0;
        for (int i = 0; i + n <= a.length; i++) {
            double s = 0;
            for (int j = i; j < i + n; j++) {
                s += a[j];
            }
            worst = Math.min(worst, s);
        }
        return worst;
    }

    static double maxDrawdown(double[] a) {
        double cum = 0;
        double peak = 0;
        double dd = 0;
        for (double d : a) {
            cum += d;
            peak = Math.max(peak, cum);
            dd = Math.min(dd, cum - peak);
        }
        return dd;
    }
}
