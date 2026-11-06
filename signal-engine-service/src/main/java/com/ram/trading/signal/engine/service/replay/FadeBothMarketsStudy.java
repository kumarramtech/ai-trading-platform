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

/**
 * Round 5 (2026-11-06): can the live FADE book (C1: ranks 501-600, the 5 biggest up >= 2%
 * vs NIFTY at 09:45, covered at 15:00, Rs 40k each, no stop) make money on both up and down
 * days? Each row changes one thing against R0, the book as it runs:
 *
 *   H  buy NIFTY for the value of the shorts, leaving the stocks' move against the index
 *   W  short only once the stock turns: first close by 11:00 below its VWAP or below its
 *      09:31-09:45 low, filled at the next minute's open
 *   S  a stop per trade
 *   M  skip the day when NIFTY is already strongly up at 09:45
 *   X  the hedge combined with the turn or a stop
 *
 * Fixed before the run. Day types are for judging only; only the M rows act on the market,
 * and only on what is known at 09:45. The hedge is priced as an ETF at 1/100 of the index,
 * with the same slippage and intraday equity charges, and one order for all legs sharing
 * an entry and exit minute.
 */
final class FadeBothMarketsStudy {

    private static final double NOTIONAL = FadeLookAheadStudy.NOTIONAL;
    private static final int POOL_FROM = 500;
    private static final int POOL_TO = 600;
    private static final int PICKS = 5;
    private static final double PROXY_DIVISOR = 100;
    private static final LocalTime LOW_FROM = LocalTime.of(9, 31);
    private static final LocalTime WAIT_UNTIL = LocalTime.of(11, 0);
    private static final LocalTime LAST = LocalTime.of(14, 59);
    private static final double STRONG_DAY_PCT = 0.75;

    // The bar a row must clear, besides making money on each kind of day.
    private static final int MIN_TRADES = 300;
    private static final double MIN_NET_BPS = 15;
    private static final double MIN_T = 2;
    private static final double MIN_RS_PER_DAY = 300;
    private static final double WORST_DAY_LIMIT = -10_000;

    enum Entry { AT_0945, BELOW_VWAP, BELOW_15MIN_LOW }

    /** {@code skipFromPrevClose}, {@code skipFromOpen}: no trades when NIFTY at 09:45 is up that % or more; NaN = off. */
    record Rule(String name, Entry entry, double stopPct, boolean hedge, double skipFromPrevClose, double skipFromOpen) {
        static Rule of(String name, Entry entry, double stopPct, boolean hedge) {
            return new Rule(name, entry, stopPct, hedge, Double.NaN, Double.NaN);
        }
    }

    static final List<Rule> RULES = List.of(
            Rule.of("R0 live book (must match Round 4 C1)", Entry.AT_0945, 0, false),
            Rule.of("H1 hedged with NIFTY", Entry.AT_0945, 0, true),
            Rule.of("W1 short below VWAP", Entry.BELOW_VWAP, 0, false),
            Rule.of("W2 short below the 09:31-09:45 low", Entry.BELOW_15MIN_LOW, 0, false),
            Rule.of("S2 stop 2%", Entry.AT_0945, 2, false),
            Rule.of("S3 stop 3%", Entry.AT_0945, 3, false),
            Rule.of("S4 stop 4%", Entry.AT_0945, 4, false),
            new Rule("M1 skip: NIFTY 09:45 >= +0.75% vs prev close", Entry.AT_0945, 0, false, 0.75, Double.NaN),
            new Rule("M2 skip: NIFTY 09:45 >= +0.25% vs open", Entry.AT_0945, 0, false, Double.NaN, 0.25),
            Rule.of("X1 hedged, short below VWAP", Entry.BELOW_VWAP, 0, true),
            Rule.of("X2 hedged, stop 3%", Entry.AT_0945, 3, true));

    /** A session and its market: NIFTY's % move over the whole day and after 09:45, for judging only. */
    record Day(LocalDate day, double wholeMove, double afterEntryMove, List<FadeLookAheadStudy.Trade> trades) {
    }

    record Entered(double price, LocalTime signal) {
    }

    private record Leg(LocalTime entryTime, LocalTime exitTime, double value, double gross, double cost) {
    }

    private FadeBothMarketsStudy() {
    }

    /** Null: no session (too few liquid stocks or no NIFTY). */
    static Day replayDay(LocalDate day, Map<String, List<MinuteCandle>> daySeries, List<MinuteCandle> nifty,
                         Double prevNiftyClose, DailyBars daily, double minPrice, double minTurnoverCrore,
                         double slippagePct, IntradayChargesCalculator charges) {
        OpeningScreen.Ranking ranking = OpeningScreen.rank(day, daySeries, nifty, daily, minPrice, minTurnoverCrore);
        if (ranking == null || ranking.ranked().size() < 550) {
            return null;
        }
        List<List<MinuteCandle>> picks = ranking.ranked().stream()
                .filter(r -> r.rank() >= POOL_FROM && r.rank() < POOL_TO && r.rel() >= FadeLookAheadStudy.MIN_MOVE)
                .sorted(Comparator.comparingDouble(OpeningScreen.Ranked::rel).reversed())
                .limit(PICKS).map(OpeningScreen.Ranked::series).toList();
        return evaluate(day, picks, nifty, prevNiftyClose, slippagePct, charges);
    }

    static Day evaluate(LocalDate day, List<List<MinuteCandle>> picks, List<MinuteCandle> nifty, Double prevNiftyClose,
                        double slippagePct, IntradayChargesCalculator charges) {
        List<FadeLookAheadStudy.Trade> trades = new ArrayList<>();
        for (Rule rule : RULES) {
            trades.addAll(ruleTrades(day, rule, picks, nifty, prevNiftyClose, slippagePct, charges));
        }
        double open = nifty.get(0).getOpen();
        double start = prevNiftyClose != null ? prevNiftyClose : open;
        Double at945 = IntradaySignalStudy.closeAt(nifty, OpeningScreen.ENTRY);
        Double last = IntradaySignalStudy.closeAt(nifty, LAST);
        double end = last != null ? last : nifty.get(nifty.size() - 1).getClose();
        double fromEntry = at945 != null ? (end - at945) / at945 * 100 : 0;
        return new Day(day, (end - start) / start * 100, fromEntry, trades);
    }

    static List<FadeLookAheadStudy.Trade> ruleTrades(LocalDate day, Rule rule, List<List<MinuteCandle>> picks,
                                                     List<MinuteCandle> nifty, Double prevNiftyClose,
                                                     double slippagePct, IntradayChargesCalculator charges) {
        if (skipped(rule, nifty, prevNiftyClose)) {
            return List.of();
        }
        List<Leg> legs = new ArrayList<>();
        for (List<MinuteCandle> series : picks) {
            Leg leg = shortLeg(series, rule, slippagePct, charges);
            if (leg != null) {
                legs.add(leg);
            }
        }
        // One hedge order per entry and exit minute, its result shared by value.
        Map<String, List<Leg>> groups = new LinkedHashMap<>();
        for (Leg leg : legs) {
            groups.computeIfAbsent(rule.hedge() ? leg.entryTime() + "-" + leg.exitTime() : "all", k -> new ArrayList<>())
                    .add(leg);
        }
        List<FadeLookAheadStudy.Trade> out = new ArrayList<>();
        groups.forEach((key, group) -> {
            double total = group.stream().mapToDouble(Leg::value).sum();
            double[] hedge = rule.hedge() ? hedgeResult(group.get(0), total, nifty, slippagePct, charges) : new double[2];
            for (Leg leg : group) {
                double share = leg.value() / total;
                double gross = leg.gross() + hedge[0] * share;
                double net = gross - leg.cost() - hedge[1] * share;
                out.add(new FadeLookAheadStudy.Trade(day, rule.name(), gross / leg.value() * 1e4,
                        net / leg.value() * 1e4, net));
            }
        });
        return out;
    }

    private static boolean skipped(Rule rule, List<MinuteCandle> nifty, Double prevNiftyClose) {
        Double at945 = IntradaySignalStudy.closeAt(nifty, OpeningScreen.ENTRY);
        if (at945 == null) {
            return false;
        }
        double open = nifty.get(0).getOpen();
        boolean fromOpen = !Double.isNaN(rule.skipFromOpen()) && (at945 - open) / open * 100 >= rule.skipFromOpen();
        boolean fromPrev = !Double.isNaN(rule.skipFromPrevClose()) && prevNiftyClose != null
                && (at945 - prevNiftyClose) / prevNiftyClose * 100 >= rule.skipFromPrevClose();
        return fromOpen || fromPrev;
    }

    /** The long leg's gross and cost for shorts worth {@code value}; zero when NIFTY has no price then. */
    private static double[] hedgeResult(Leg leg, double value, List<MinuteCandle> nifty, double slippagePct,
                                        IntradayChargesCalculator charges) {
        Double in = IntradaySignalStudy.closeAt(nifty, leg.entryTime());
        Double out = IntradaySignalStudy.closeAt(nifty, leg.exitTime());
        if (in == null || out == null) {
            return new double[2];
        }
        double buy = in / PROXY_DIVISOR * (1 + slippagePct / 100);
        double sell = out / PROXY_DIVISOR * (1 - slippagePct / 100);
        int qty = (int) Math.floor(value / buy);
        return new double[]{(sell - buy) * qty, charges.roundTrip("BUY", buy, sell, qty).getTotal()};
    }

    /** The short from its entry to the 14:59 close or the stop (the open on a gap through it). */
    private static Leg shortLeg(List<MinuteCandle> series, Rule rule, double slippagePct,
                                IntradayChargesCalculator charges) {
        Entered entered = entered(series, rule.entry());
        Double close = IntradaySignalStudy.closeAt(series, LAST);
        if (entered == null || close == null) {
            return null;
        }
        double out = close;
        LocalTime exitTime = LAST;
        if (rule.stopPct() > 0) {
            double stop = entered.price() * (1 + rule.stopPct() / 100);
            for (MinuteCandle c : series) {
                LocalTime t = c.getMinute().toLocalTime();
                if (!t.isAfter(entered.signal())) {
                    continue;
                }
                if (t.isAfter(LAST)) {
                    break;
                }
                if (c.getHigh() >= stop) {
                    out = Math.max(stop, c.getOpen());
                    exitTime = t;
                    break;
                }
            }
        }
        double entry = entered.price() * (1 - slippagePct / 100);
        double exit = out * (1 + slippagePct / 100);
        int qty = (int) Math.floor(NOTIONAL / entry);
        if (qty < 1) {
            return null;
        }
        return new Leg(entered.signal(), exitTime, entry * qty, (entry - exit) * qty,
                charges.roundTrip("SELL", entry, exit, qty).getTotal());
    }

    /** Null when the stock never gave the signal by 11:00. */
    static Entered entered(List<MinuteCandle> series, Entry entry) {
        if (entry == Entry.AT_0945) {
            Double in = IntradaySignalStudy.closeAt(series, OpeningScreen.ENTRY);
            return in == null ? null : new Entered(in, OpeningScreen.ENTRY);
        }
        double value = 0, volume = 0, low = Double.MAX_VALUE;
        for (int i = 0; i < series.size(); i++) {
            MinuteCandle c = series.get(i);
            LocalTime t = c.getMinute().toLocalTime();
            if (t.isAfter(WAIT_UNTIL)) {
                return null;
            }
            double v = c.getVolume() == null ? 0 : c.getVolume();
            value += (c.getHigh() + c.getLow() + c.getClose()) / 3 * v;
            volume += v;
            if (!t.isAfter(OpeningScreen.ENTRY)) {
                if (!t.isBefore(LOW_FROM)) {
                    low = Math.min(low, c.getLow());
                }
                continue;
            }
            boolean turned = entry == Entry.BELOW_VWAP ? volume > 0 && c.getClose() < value / volume : c.getClose() < low;
            if (turned) {
                return i + 1 < series.size() ? new Entered(series.get(i + 1).getOpen(), t) : null;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- report

    static String render(List<Day> days) {
        if (days.isEmpty()) {
            return "ROUND 5 FADE IN BOTH MARKETS: no sessions (the universe must cover turnover ranks 1-1000)\n";
        }
        LocalDate split = days.get(days.size() / 2).day();
        StringBuilder out = new StringBuilder();
        out.append("ROUND 5 FADE IN BOTH MARKETS: the live book (pit 501-600, 5 biggest, Rs 40k each) with one change a row; net of slippage and charges\n");
        out.append(String.format("%d sessions, halves split at %s; Rs per session on: up/down days = NIFTY prev close to 15:00 (%d up, %d down),"
                        + " after = NIFTY 09:45 to 15:00 (%d up, %d down), strong = whole day >= +%.2f%% (%d)%n%n",
                days.size(), split, count(days, d -> d.wholeMove() >= 0), count(days, d -> d.wholeMove() < 0),
                count(days, d -> d.afterEntryMove() >= 0), count(days, d -> d.afterEntryMove() < 0), STRONG_DAY_PCT,
                count(days, d -> d.wholeMove() >= STRONG_DAY_PCT)));
        out.append(String.format("%-46s %5s %7s %7s %6s %7s %7s %6s %8s %9s %8s %8s %8s %8s %8s  %s%n", "rule", "n", "gross", "net", "t",
                "H1 net", "H2 net", "win%", "Rs/day", "worstDay", "up", "down", "afterUp", "afterDn", "strong", "verdict"));
        for (Rule rule : RULES) {
            out.append(row(rule, days, split));
        }
        out.append(String.format("%nbps of the short value per trade; t on the daily average net. PASS = n >= %d, net >= %.0f bps, t >= %.0f, both halves > 0,"
                        + " Rs/day >= %.0f, worst day >= %.0f, and Rs > 0 on up, down, afterUp and afterDn days.%n",
                MIN_TRADES, MIN_NET_BPS, MIN_T, MIN_RS_PER_DAY, WORST_DAY_LIMIT));
        return out.toString();
    }

    private static String row(Rule rule, List<Day> days, LocalDate split) {
        List<FadeLookAheadStudy.Trade> list = new ArrayList<>();
        Map<LocalDate, double[]> byDay = new TreeMap<>();
        for (Day d : days) {
            for (FadeLookAheadStudy.Trade t : d.trades()) {
                if (t.rule().equals(rule.name())) {
                    list.add(t);
                    double[] x = byDay.computeIfAbsent(t.day(), k -> new double[3]);
                    x[0] += t.netBps();
                    x[1]++;
                    x[2] += t.netRs();
                }
            }
        }
        if (list.isEmpty()) {
            return String.format("%-46s %5d%n", rule.name(), 0);
        }
        double gross = list.stream().mapToDouble(FadeLookAheadStudy.Trade::grossBps).average().orElse(0);
        double net = list.stream().mapToDouble(FadeLookAheadStudy.Trade::netBps).average().orElse(0);
        double h1 = list.stream().filter(t -> t.day().isBefore(split)).mapToDouble(FadeLookAheadStudy.Trade::netBps).average().orElse(Double.NaN);
        double h2 = list.stream().filter(t -> !t.day().isBefore(split)).mapToDouble(FadeLookAheadStudy.Trade::netBps).average().orElse(Double.NaN);
        double win = list.stream().filter(t -> t.netBps() > 0).count() * 100.0 / list.size();
        double rsPerDay = list.stream().mapToDouble(FadeLookAheadStudy.Trade::netRs).sum() / days.size();
        double worst = byDay.values().stream().mapToDouble(x -> x[2]).min().orElse(0);
        double t = tStat(byDay.values().stream().mapToDouble(x -> x[0] / x[1]).toArray());
        double up = rsPerSession(days, byDay, d -> d.wholeMove() >= 0);
        double down = rsPerSession(days, byDay, d -> d.wholeMove() < 0);
        double afterUp = rsPerSession(days, byDay, d -> d.afterEntryMove() >= 0);
        double afterDown = rsPerSession(days, byDay, d -> d.afterEntryMove() < 0);
        double strong = rsPerSession(days, byDay, d -> d.wholeMove() >= STRONG_DAY_PCT);

        List<String> fails = new ArrayList<>();
        addIf(fails, list.size() < MIN_TRADES, "n");
        addIf(fails, net < MIN_NET_BPS, "net");
        addIf(fails, !(t >= MIN_T), "t");
        addIf(fails, !(h1 > 0), "H1");
        addIf(fails, !(h2 > 0), "H2");
        addIf(fails, rsPerDay < MIN_RS_PER_DAY, "Rs/day");
        addIf(fails, worst < WORST_DAY_LIMIT, "worst");
        addIf(fails, up <= 0, "up");
        addIf(fails, down <= 0, "down");
        addIf(fails, afterUp <= 0, "afterUp");
        addIf(fails, afterDown <= 0, "afterDn");
        String verdict = fails.isEmpty() ? "PASS" : "fails " + String.join(",", fails);

        return String.format("%-46s %5d %7.1f %7.1f %6.2f %7.1f %7.1f %6.1f %8.0f %9.0f %8.0f %8.0f %8.0f %8.0f %8.0f  %s%n",
                rule.name(), list.size(), gross, net, t, h1, h2, win, rsPerDay, worst, up, down, afterUp, afterDown, strong, verdict);
    }

    private static void addIf(List<String> list, boolean condition, String name) {
        if (condition) {
            list.add(name);
        }
    }

    private static int count(List<Day> days, java.util.function.Predicate<Day> type) {
        return (int) days.stream().filter(type).count();
    }

    /** Average Rs over every session of the type, days without a trade counted as zero. */
    private static double rsPerSession(List<Day> days, Map<LocalDate, double[]> byDay, java.util.function.Predicate<Day> type) {
        return days.stream().filter(type)
                .mapToDouble(d -> byDay.containsKey(d.day()) ? byDay.get(d.day())[2] : 0).average().orElse(0);
    }

    private static double tStat(double[] a) {
        if (a.length < 3) {
            return Double.NaN;
        }
        double m = java.util.Arrays.stream(a).average().orElse(0);
        double var = java.util.Arrays.stream(a).map(x -> (x - m) * (x - m)).sum() / (a.length - 1);
        return var <= 0 ? Double.NaN : m / Math.sqrt(var / a.length);
    }
}
