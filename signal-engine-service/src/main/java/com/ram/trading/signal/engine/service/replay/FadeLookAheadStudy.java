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
import java.util.function.Predicate;

/**
 * Round 4 (2026-11-03): why the FADE edge of Round 2 (+44 bps gross, t 3.9) vanished in
 * Round 3. Round 2 took its pool from the stocks ranked 501-1000 by turnover in the 30
 * days BEFORE THE END of the replay (October), then the day's top 100 of those; Round 3
 * ranked on each day's past only and kept the 5 biggest movers with stops. Every rule
 * here is the same short (up >= 2% vs NIFTY at 09:45, covered at 15:00, Rs 40k each, no
 * stop unless named), so the rows differ only in the pool, the pick and the stop:
 *
 *   A  look-ahead pools (end-of-period rank): A1 replicates Round 2, A2 the plain cut
 *   F  the split of the point-in-time pool by end rank: if F1 (stocks that later fell to
 *      ranks 501-1000) wins and F2 (stocks still in the top 500 later) loses, the edge was
 *      the knowledge of which stocks would lose attention: look-ahead, not tradeable
 *   B  point-in-time pools of other ranks
 *   C  pick and stop inside the point-in-time pools
 *   D  attention fading, knowable at 09:45: last 5 sessions' turnover vs the 20-session average
 *   E  news vs drift: the gap from yesterday's close
 *
 * Fixed before the run. Only point-in-time rows (B-E) can be traded; A and F are marked.
 */
final class FadeLookAheadStudy {

    static final double NOTIONAL = 40_000;
    static final double MIN_MOVE = 2.0;
    private static final LocalTime EXIT = LocalTime.of(15, 0);

    record Trade(LocalDate day, String rule, double grossBps, double netBps, double netRs) {
    }

    /** One candidate at 09:45 with what is known about it. */
    record Candidate(OpeningScreen.Ranked r, int endRank, double spike, double gapPct) {
        int pit() {
            return r.rank();
        }
    }

    private record Rule(String name, boolean lookAhead, Predicate<Candidate> pool, int top, double stopPct) {
    }

    private static boolean pit(Candidate c, int from, int to) {
        return c.pit() >= from && c.pit() < to;
    }

    private static boolean end(Candidate c, int from, int to) {
        return c.endRank() >= from && c.endRank() < to;
    }

    static final List<Rule> RULES = List.of(
            new Rule("A1 Round 2 replica: end 501-1000, day's top 100 of them", true, null, 0, 0),
            new Rule("A2 end rank 501-600", true, c -> end(c, 500, 600), 0, 0),
            new Rule("F1 pit 501-600 AND end 501-1000 (later less traded)", true,
                    c -> pit(c, 500, 600) && end(c, 500, 1000), 0, 0),
            new Rule("F2 pit 501-600 AND end 1-500 (later more traded)", true,
                    c -> pit(c, 500, 600) && end(c, 0, 500), 0, 0),
            new Rule("B1 pit 501-600 (Round 3 pool, every candidate)", false, c -> pit(c, 500, 600), 0, 0),
            new Rule("B2 pit 401-700", false, c -> pit(c, 400, 700), 0, 0),
            new Rule("B3 pit 501-1000", false, c -> pit(c, 500, 1000), 0, 0),
            new Rule("B4 pit 201-500", false, c -> pit(c, 200, 500), 0, 0),
            new Rule("B5 pit 701-1000", false, c -> pit(c, 700, 1000), 0, 0),
            new Rule("C1 pit 501-600, 5 biggest (Round 3 pick)", false, c -> pit(c, 500, 600), 5, 0),
            new Rule("C2 pit 501-600, up 2-4% only", false, c -> pit(c, 500, 600) && c.r().rel() < 4, 0, 0),
            new Rule("C3 pit 501-600, stop 3%", false, c -> pit(c, 500, 600), 0, 3),
            new Rule("C4 pit 501-600, stop 5%", false, c -> pit(c, 500, 600), 0, 5),
            new Rule("C5 pit 501-1000, 5 biggest", false, c -> pit(c, 500, 1000), 5, 0),
            new Rule("C6 pit 501-1000, up 2-4% only", false, c -> pit(c, 500, 1000) && c.r().rel() < 4, 0, 0),
            new Rule("D1 pit 501-1000, turnover 5d/20d >= 1.5 (spike)", false,
                    c -> pit(c, 500, 1000) && c.spike() >= 1.5, 0, 0),
            new Rule("D2 pit 501-1000, turnover 5d/20d < 1.0 (quiet)", false,
                    c -> pit(c, 500, 1000) && c.spike() < 1.0, 0, 0),
            new Rule("D3 pit 501-600, turnover 5d/20d >= 1.5", false,
                    c -> pit(c, 500, 600) && c.spike() >= 1.5, 0, 0),
            new Rule("E1 pit 501-1000, gap up >= 2% (news)", false,
                    c -> pit(c, 500, 1000) && c.gapPct() >= 2, 0, 0),
            new Rule("E2 pit 501-1000, gap < 1% (intraday drift)", false,
                    c -> pit(c, 500, 1000) && c.gapPct() < 1, 0, 0));

    private FadeLookAheadStudy() {
    }

    /** endRank: 0-based rank of each universe symbol by turnover before the replay's end date. Null: no session (too few liquid stocks or no NIFTY). */
    static List<Trade> replayDay(LocalDate day, Map<String, List<MinuteCandle>> daySeries, List<MinuteCandle> nifty,
                                 DailyBars daily, Map<String, Integer> endRank, double minPrice,
                                 double minTurnoverCrore, double slippagePct, IntradayChargesCalculator charges) {
        OpeningScreen.Ranking ranking = OpeningScreen.rank(day, daySeries, nifty, daily, minPrice, minTurnoverCrore);
        if (ranking == null || ranking.ranked().size() < 550) {
            return null;
        }
        List<Candidate> movers = new ArrayList<>();
        List<Candidate> endPool = new ArrayList<>();
        for (OpeningScreen.Ranked r : ranking.ranked()) {
            double spike = OpeningScreen.turnoverSpike(daily, r, day);
            double gap = OpeningScreen.gapPct(r);
            Candidate c = new Candidate(r, endRank.getOrDefault(r.symbol(), -1), spike, gap);
            if (end(c, 500, 1000)) {
                endPool.add(c);
            }
            if (r.rel() >= MIN_MOVE) {
                movers.add(c);
            }
        }
        // A1: Round 2's pool was its universe (end ranks 501-1000), cut to the day's 100 most traded.
        java.util.Set<String> replica = new java.util.HashSet<>();
        endPool.stream().limit(100).forEach(c -> replica.add(c.r().symbol()));

        List<Trade> out = new ArrayList<>();
        for (Rule rule : RULES) {
            Predicate<Candidate> pool = rule.pool() != null ? rule.pool() : c -> replica.contains(c.r().symbol());
            List<Candidate> picks = movers.stream().filter(pool)
                    .sorted(Comparator.comparingDouble((Candidate c) -> c.r().rel()).reversed()).toList();
            if (rule.top() > 0 && picks.size() > rule.top()) {
                picks = picks.subList(0, rule.top());
            }
            for (Candidate c : picks) {
                Trade t = shortTrade(day, rule.name(), c.r().series(), rule.stopPct(), slippagePct, charges);
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** Short at the 09:45 close, covered at the 14:59 close or the stop (the open on a gap through it). */
    static Trade shortTrade(LocalDate day, String rule, List<MinuteCandle> series, double stopPct,
                            double slippagePct, IntradayChargesCalculator charges) {
        Double in = IntradaySignalStudy.closeAt(series, OpeningScreen.ENTRY);
        Double out = IntradaySignalStudy.closeAt(series, EXIT.minusMinutes(1));
        if (in == null || out == null) {
            return null;
        }
        if (stopPct > 0) {
            double stop = in * (1 + stopPct / 100);
            for (MinuteCandle c : series) {
                LocalTime t = c.getMinute().toLocalTime();
                if (!t.isAfter(OpeningScreen.ENTRY)) {
                    continue;
                }
                if (!t.isBefore(EXIT)) {
                    break;
                }
                if (c.getHigh() >= stop) {
                    out = Math.max(stop, c.getOpen());
                    break;
                }
            }
        }
        double entry = in * (1 - slippagePct / 100);
        double exit = out * (1 + slippagePct / 100);
        int qty = (int) Math.floor(NOTIONAL / entry);
        if (qty < 1) {
            return null;
        }
        double value = entry * qty;
        double gross = (entry - exit) * qty;
        double cost = charges.roundTrip("SELL", entry, exit, qty).getTotal();
        return new Trade(day, rule, gross / value * 1e4, (gross - cost) / value * 1e4, gross - cost);
    }

    // -------------------------------------------------------------- report

    static String render(List<Trade> trades, List<LocalDate> sessions) {
        if (sessions.isEmpty()) {
            return "ROUND 4 FADE LOOK-AHEAD: no sessions (the universe must cover turnover ranks 1-1000)\n";
        }
        LocalDate split = sessions.get(sessions.size() / 2);
        Map<String, List<Trade>> byRule = new LinkedHashMap<>();
        RULES.forEach(r -> byRule.put(r.name(), new ArrayList<>()));
        trades.forEach(t -> byRule.computeIfAbsent(t.rule(), k -> new ArrayList<>()).add(t));

        StringBuilder out = new StringBuilder();
        out.append("ROUND 4 FADE LOOK-AHEAD: short up >= 2% vs NIFTY at 09:45, cover 15:00, Rs 40k each, net of 0.03% slippage and charges\n")
                .append("pit = rank by turnover in the 20 sessions before the day; end = rank by turnover before the replay's end (look-ahead)\n")
                .append("halves split at ").append(split).append(", ").append(sessions.size()).append(" sessions\n\n");
        out.append(String.format("%-58s %5s %5s %7s %7s %6s %7s %7s %7s %8s %9s  %s%n",
                "rule", "n", "/day", "gross", "net", "t", "H1 net", "H2 net", "win%", "Rs/day", "worstDay", "verdict"));
        for (Rule rule : RULES) {
            List<Trade> list = byRule.get(rule.name());
            if (list.isEmpty()) {
                out.append(String.format("%-58s %5d%n", rule.name(), 0));
                continue;
            }
            Map<LocalDate, double[]> days = new TreeMap<>();
            for (Trade t : list) {
                double[] d = days.computeIfAbsent(t.day(), k -> new double[3]);
                d[0] += t.netBps();
                d[1]++;
                d[2] += t.netRs();
            }
            double[] dailyMean = days.values().stream().mapToDouble(d -> d[0] / d[1]).toArray();
            double gross = list.stream().mapToDouble(Trade::grossBps).average().orElse(0);
            double net = list.stream().mapToDouble(Trade::netBps).average().orElse(0);
            double h1 = list.stream().filter(t -> t.day().isBefore(split)).mapToDouble(Trade::netBps).average().orElse(Double.NaN);
            double h2 = list.stream().filter(t -> !t.day().isBefore(split)).mapToDouble(Trade::netBps).average().orElse(Double.NaN);
            double win = list.stream().filter(t -> t.netBps() > 0).count() * 100.0 / list.size();
            double rsPerDay = list.stream().mapToDouble(Trade::netRs).sum() / sessions.size();
            double worst = days.values().stream().mapToDouble(d -> d[2]).min().orElse(0);
            double t = tStat(dailyMean);
            String verdict = rule.lookAhead() ? "look-ahead (not tradeable)"
                    : list.size() >= 300 && net >= 15 && t >= 2 && h1 > 0 && h2 > 0 ? "PASS"
                    : net > 0 && h1 > 0 && h2 > 0 ? "positive, below the bar" : "-";
            out.append(String.format("%-58s %5d %5.1f %7.1f %7.1f %6.2f %7.1f %7.1f %7.1f %8.0f %9.0f  %s%n",
                    rule.name(), list.size(), list.size() / (double) sessions.size(), gross, net, t, h1, h2, win,
                    rsPerDay, worst, verdict));
        }
        out.append("\nbps of notional per trade; t on the daily average net (days with trades); Rs/day = all trades at Rs 40k / all sessions.\n")
                .append("PASS (point-in-time rows only) = n >= 300, net >= 15 bps, t >= 2, both halves net > 0.\n");
        return out.toString();
    }

    private static double tStat(double[] a) {
        if (a.length < 3) {
            return Double.NaN;
        }
        double m = java.util.Arrays.stream(a).average().orElse(0);
        double var = java.util.Arrays.stream(a).map(x -> (x - m) * (x - m)).sum() / (a.length - 1);
        return var <= 0 ? Double.NaN : m / Math.sqrt(var / a.length);
    }

    /** Sessions in order (for callers collecting them). */
    static List<LocalDate> sorted(java.util.Collection<LocalDate> days) {
        return new ArrayList<>(new TreeSet<>(days));
    }
}
