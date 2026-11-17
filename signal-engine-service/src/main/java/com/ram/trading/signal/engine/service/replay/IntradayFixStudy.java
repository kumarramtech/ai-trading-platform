package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.StrategyReplayService.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Intraday fix study (research only, a separate study; the frozen intraday rules are not changed).
 *
 * PRE-REGISTERED on 2026-11-17, before any result was seen. The four fixes come from the 162 paper
 * trades (Sep 28 - Nov 17), so they are judged on an EARLIER period those trades never saw:
 * 2025-10-01 .. 2026-09-25, top 500 stocks by turnover, the live ORB and JUDAS setups replayed.
 *
 *   FIX_1000 / FIX_1030 = all four fixes together, with the entry cut-off at 10:00 / 10:30:
 *     1. no entry at or after the cut-off;
 *     2. no target and no structure exit: out at the stop or at 15:00;
 *     3. stop at least 1.2% from the entry (the live stop if wider), size cut to keep Rs 250 risk;
 *     4. entry price >= Rs 200 and 20-day average turnover >= Rs 25 crore.
 *   Each fix alone is reported too, for learning only; it decides nothing.
 *
 * PASS (after charges and 0.03% slippage a side, like paper): at least 100 trades, mean >= +0.10R
 * a trade, t >= 2 on daily totals, and both halves of the period (split by trading day) positive.
 * DECISION: FIX_1030 if it passes; else FIX_1000 if it passes; else neither, and the four fixes are
 * dropped (no new variants of them afterwards). A passing variant then goes to a separate paper book.
 */
@Slf4j
@Service
public class IntradayFixStudy {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate FROM = LocalDate.of(2025, 10, 1);
    static final LocalDate TO = LocalDate.of(2026, 9, 25);
    static final int UNIVERSE = 500;
    static final double MIN_STOP_PCT = 1.2;
    static final double MIN_PRICE = 200;
    static final double MIN_TURNOVER = 25e7; // Rs 25 crore
    static final int MIN_TRADES = 100;
    static final double MIN_MEAN_R = 0.10;
    static final double MIN_T = 2.0;
    static final LocalTime SQUARE_OFF = LocalTime.of(15, 0);

    record Variant(String name, LocalTime cutoff, boolean holdToClose, boolean wideStop, boolean liquidOnly,
                   boolean decides) {
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant("BASE (live rules)", null, false, false, false, false),
            new Variant("FIX_1030 (all four, cut-off 10:30)", LocalTime.of(10, 30), true, true, true, true),
            new Variant("FIX_1000 (all four, cut-off 10:00)", LocalTime.of(10, 0), true, true, true, true),
            new Variant("only cut-off 10:30", LocalTime.of(10, 30), false, false, false, false),
            new Variant("only cut-off 10:00", LocalTime.of(10, 0), false, false, false, false),
            new Variant("only hold to stop/15:00", null, true, false, false, false),
            new Variant("only stop >= 1.2%", null, false, true, false, false),
            new Variant("only price >= 200 & turnover >= 25cr", null, false, false, true, false));

    /** One re-simulated trade. */
    record Trade(LocalDate day, String strategy, double netR) {
    }

    record Stats(String name, int trades, int days, double meanR, double t, double half1, double half2,
                 double winPct, double grossR) {
        boolean passes() {
            return trades >= MIN_TRADES && meanR >= MIN_MEAN_R && t >= MIN_T && half1 > 0 && half2 > 0;
        }
    }

    private final StrategyReplayService replay;
    private final JdbcTemplate jdbc;
    private final IntradayChargesCalculator charges;
    private final AtomicReference<String> status = new AtomicReference<>("not run yet");

    @Value("${trading.risk.risk-per-trade:250}")
    private double risk = 250;

    @Value("${paper.trading.slippage-pct:0.03}")
    private double slippagePct = 0.03;

    @Value("${trading.replay.max-notional:500000}")
    private double maxNotional = 500000;

    public IntradayFixStudy(StrategyReplayService replay, JdbcTemplate jdbc, IntradayChargesCalculator charges) {
        this.replay = replay;
        this.jdbc = jdbc;
        this.charges = charges;
    }

    public String status() {
        return status.get();
    }

    public static boolean marketHours(LocalDateTime now) {
        LocalTime t = now.toLocalTime();
        return now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY
                && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35));
    }

    public synchronized boolean start() {
        if (status.get().startsWith("RUNNING")) {
            return false;
        }
        status.set("RUNNING | starting " + LocalDateTime.now(IST).withNano(0));
        Mono.fromCallable(this::run)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(report -> {
                    status.set(report);
                    log.info("INTRADAY FIX STUDY DONE\n{}", report);
                }, error -> {
                    log.error("INTRADAY FIX STUDY FAILED", error);
                    status.set("FAILED | " + error.getMessage());
                });
        return true;
    }

    private String run() {
        StrategyReplayService.LiveSetups live = replay.replayLiveSetups(FROM, TO, UNIVERSE,
                p -> {
                    status.set("RUNNING | " + p);
                    log.info("INTRADAY FIX STUDY | {}", p);
                },
                () -> !marketHours(LocalDateTime.now(IST)));
        Set<String> symbols = new HashSet<>();
        live.results().forEach(r -> symbols.add(r.symbol()));
        DailyBars daily = DailyBars.load(jdbc, symbols, FROM.minusDays(45), TO.plusDays(1));
        List<Stats> stats = new ArrayList<>();
        for (Variant v : VARIANTS) {
            List<Trade> trades = new ArrayList<>();
            double gross = 0;
            for (Result r : live.results()) {
                DailyBars.Stats s = daily.statsBefore(r.symbol(), r.day());
                double[] t = simulate(r, v, s == null ? Double.NaN : s.avgTurnover20(), risk, slippagePct,
                        maxNotional, charges);
                if (t != null) {
                    trades.add(new Trade(r.day(), r.strategy(), t[0]));
                    gross += t[1];
                }
            }
            stats.add(stats(v.name(), trades, trades.isEmpty() ? 0 : gross / trades.size()));
        }
        return render(live.sessions(), live.results().size(), stats);
    }

    /**
     * Re-simulates one replayed setup under a variant from its stored price path.
     * Returns {net R, gross R}, or null when the variant does not take the trade.
     */
    static double[] simulate(Result r, Variant v, double avgTurnover20, double risk, double slippagePct,
                             double maxNotional, IntradayChargesCalculator charges) {
        if (v.cutoff() != null && !r.time().isBefore(v.cutoff())) {
            return null;
        }
        boolean buy = "BUY".equals(r.direction());
        if (v.liquidOnly() && (r.entry() < MIN_PRICE || Double.isNaN(avgTurnover20) || avgTurnover20 < MIN_TURNOVER)) {
            return null;
        }
        double entry = r.entry();
        double stopDist = Math.abs(entry - r.stop());
        if (v.wideStop()) {
            stopDist = Math.max(stopDist, entry * MIN_STOP_PCT / 100);
        }
        if (stopDist <= 0) {
            return null;
        }
        double stop = buy ? entry - stopDist : entry + stopDist;
        int quantity = (int) Math.min(Math.floor(risk / stopDist), Math.floor(maxNotional / entry));
        if (quantity < 1) {
            return null;
        }
        boolean useTarget = !v.holdToClose() && !Double.isNaN(r.target());
        float[] hi = r.pathHigh(), lo = r.pathLow(), cl = r.pathClose();
        double exitRef = cl.length == 0 ? entry : cl[cl.length - 1];
        for (int i = 0; i < cl.length; i++) {
            if (buy ? lo[i] <= stop : hi[i] >= stop) {
                exitRef = stop;
                break;
            }
            if (useTarget && (buy ? hi[i] >= r.target() : lo[i] <= r.target())) {
                exitRef = r.target();
                break;
            }
        }
        double exit = buy ? exitRef * (1 - slippagePct / 100) : exitRef * (1 + slippagePct / 100);
        double gross = (buy ? exit - entry : entry - exit) * quantity;
        double cost = charges.roundTrip(r.direction(), entry, exit, quantity).getTotal();
        return new double[]{(gross - cost) / risk, gross / risk};
    }

    static Stats stats(String name, List<Trade> trades, double grossR) {
        if (trades.isEmpty()) {
            return new Stats(name, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        TreeMap<LocalDate, Double> byDay = new TreeMap<>();
        TreeMap<LocalDate, int[]> countByDay = new TreeMap<>();
        for (Trade t : trades) {
            byDay.merge(t.day(), t.netR(), Double::sum);
            countByDay.computeIfAbsent(t.day(), d -> new int[1])[0]++;
        }
        double[] daily = byDay.values().stream().mapToDouble(Double::doubleValue).toArray();
        double mean = trades.stream().mapToDouble(Trade::netR).average().orElse(0);
        double win = trades.stream().filter(t -> t.netR() > 0).count() * 100.0 / trades.size();
        // Halves by trading day; each half's mean R per trade.
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        LocalDate split = days.get(days.size() / 2);
        double[] h1 = halfMean(trades, split, true), h2 = halfMean(trades, split, false);
        return new Stats(name, trades.size(), days.size(), mean, t(daily), h1[0], h2[0], win, grossR);
    }

    private static double[] halfMean(List<Trade> trades, LocalDate split, boolean first) {
        double sum = 0;
        int n = 0;
        for (Trade t : trades) {
            if (t.day().isBefore(split) == first) {
                sum += t.netR();
                n++;
            }
        }
        return new double[]{n == 0 ? 0 : sum / n, n};
    }

    static double t(double[] x) {
        if (x.length < 2) {
            return 0;
        }
        double m = java.util.Arrays.stream(x).average().orElse(0), ss = 0;
        for (double v : x) {
            ss += (v - m) * (v - m);
        }
        double sd = Math.sqrt(ss / (x.length - 1));
        return sd == 0 ? 0 : m / (sd / Math.sqrt(x.length));
    }

    static String decision(List<Stats> stats) {
        Map<String, Stats> by = new java.util.HashMap<>();
        stats.forEach(s -> by.put(s.name(), s));
        Stats late = by.get(VARIANTS.get(1).name()), early = by.get(VARIANTS.get(2).name());
        if (late != null && late.passes()) {
            return "DECISION  FIX_1030 PASSES: next step is a separate Intraday v2 paper book (frozen v1 untouched).";
        }
        if (early != null && early.passes()) {
            return "DECISION  FIX_1000 PASSES: next step is a separate Intraday v2 paper book (frozen v1 untouched).";
        }
        return "DECISION  NEITHER passes: the four fixes are dropped as pre-registered (no new variants of them).";
    }

    static String render(int sessions, int setups, List<Stats> stats) {
        StringBuilder out = new StringBuilder();
        out.append("INTRADAY FIX STUDY (pre-registered 2026-11-17; research only)\n");
        out.append(String.format("Period %s .. %s (before the paper trades the fixes came from), top %d stocks, "
                + "%d sessions, %d live ORB/JUDAS setups replayed%n", FROM, TO, UNIVERSE, sessions, setups));
        out.append(String.format("Pass: >= %d trades, mean >= +%.2fR net, t >= %.1f (daily totals), both halves > 0%n%n",
                MIN_TRADES, MIN_MEAN_R, MIN_T));
        out.append(String.format("%-40s %6s %5s %8s %8s %6s %8s %8s %5s  %s%n",
                "variant", "trades", "days", "net R", "gross R", "t", "half 1", "half 2", "win%", "verdict"));
        for (int i = 0; i < stats.size(); i++) {
            Stats s = stats.get(i);
            String verdict = VARIANTS.get(i).decides() ? (s.passes() ? "PASS" : "FAIL") : "info";
            out.append(String.format("%-40s %6d %5d %+8.3f %+8.3f %6.2f %+8.3f %+8.3f %5.0f  %s%n", s.name(),
                    s.trades(), s.days(), s.meanR(), s.grossR(), s.t(), s.half1(), s.half2(), s.winPct(), verdict));
        }
        if (sessions < 150) {
            out.append("\nINVALID: fewer than 150 sessions replayed (history gaps); no decision.\n");
            return out.toString();
        }
        return out.append("\n").append(decision(stats)).append("\n").toString();
    }
}
