package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse;
import com.ram.trading.signal.engine.service.replay.SwingBacktestService.DeliveryCharges;
import com.ram.trading.signal.engine.service.replay.SwingBacktestService.Series;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Momentum + Low Vol": a separate strategy study (pre-registered 2026-11-12 in the strategy
 * memory). Reads only; the live momentum list, its paper book and the swing backtest are not
 * changed (their static helpers are reused as they are).
 *
 * Everything as the live rule: point-in-time top 200 by turnover, no funds, price >= 50,
 * monthly, NIFTY > 100DMA else cash at 6.5%, Rs 0 brokerage, 0.10% slippage, delivery
 * charges, top 30 with hold-to-rank 40, next-open fills. Only the ranking differs:
 *   LIVE     6m-1m vol-adjusted momentum (the baseline)
 *   A        momentum top 60, then the 30 with the lowest 1-year daily volatility
 *   B        average of the momentum rank and the low-volatility rank
 *   CONTROL  lowest 1-year volatility only (explains the result; never a candidate)
 * Two periods: 5 years to 2026-11-03 and 5 years to 2021-10-31, universe 1,500.
 * A candidate passes only if in BOTH periods, against LIVE: (max DD >= 3 points lower and
 * %/yr at most 2 points lower) or (%/yr higher and max DD at most 2 points worse). If both
 * pass, the higher smaller-of-the-two-periods improvement (return gain + DD reduction) wins.
 */
@Service
@Slf4j
public class LowVolMomentumStudy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int TOP = 30;
    static final int HOLD_RANK = 40;
    static final int FIRST_CUT = 60;
    static final int LOOKBACK = 126;
    static final int SKIP = 21;
    static final int VOL_WINDOW = 252;
    static final int REGIME_SMA = 100;
    static final int LIQUID_RANK = 200;
    static final double CASH_YIELD = 6.5;
    static final double SLIPPAGE_PCT = 0.10;
    static final double CAPITAL = 100000;
    static final LocalDate RECENT_TO = LocalDate.of(2026, 11, 3);
    static final LocalDate OLDER_TO = LocalDate.of(2021, 10, 31);
    static final int YEARS = 5;
    static final int UNIVERSE = 1500;

    enum Variant {
        LIVE("LIVE: momentum (baseline)"),
        A("A: momentum top 60 -> 30 calmest"),
        B("B: average of momentum + low-vol ranks"),
        CONTROL("CONTROL: low volatility only");

        final String label;

        Variant(String label) {
            this.label = label;
        }
    }

    private final JdbcTemplate jdbc;
    private final WebClient stockService;
    private final AtomicReference<String> status = new AtomicReference<>("not run yet");

    @Value("${trading.replay.requests-per-second:6}")
    private int requestsPerSecond = 6;

    public LowVolMomentumStudy(JdbcTemplate jdbc, WebClient.Builder builder,
                               @Value("${stock.service.url}") String stockServiceUrl) {
        this.jdbc = jdbc;
        this.stockService = builder.clone().baseUrl(stockServiceUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
    }

    public String status() {
        return status.get();
    }

    public synchronized boolean start() {
        if (status.get().startsWith("RUNNING")) {
            return false;
        }
        status.set("RUNNING | starting " + LocalDateTime.now(IST));
        Mono.fromCallable(this::run)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(report -> {
                    status.set(report);
                    log.info("LOW-VOL MOMENTUM STUDY DONE\n{}", report);
                }, error -> {
                    log.error("LOW-VOL MOMENTUM STUDY FAILED", error);
                    status.set("FAILED | " + error.getMessage());
                });
        return true;
    }

    // ------------------------------------------------------------------ pure parts

    /** Standard deviation of daily returns over the {@code window} sessions to {@code i}; NaN when too short. */
    static double volatility(Series s, int i, int window) {
        if (i < window) {
            return Double.NaN;
        }
        double sum = 0, sq = 0;
        for (int j = i - window + 1; j <= i; j++) {
            double r = s.close()[j] / s.close()[j - 1] - 1;
            sum += r;
            sq += r * r;
        }
        double mean = sum / window;
        return Math.sqrt(Math.max(0, sq / window - mean * mean));
    }

    /** One eligible stock on a signal day. */
    record Scored(String symbol, double momentum, double vol) {
    }

    /** Pure: the variant's ranking, best first (the buffer then keeps holdings within HOLD_RANK). */
    static List<String> ranking(Variant v, List<Scored> stocks) {
        List<Scored> mom = new ArrayList<>(stocks);
        mom.sort(Comparator.comparingDouble(Scored::momentum).reversed());
        List<Scored> calm = new ArrayList<>(stocks);
        calm.sort(Comparator.comparingDouble(Scored::vol));
        List<String> out = new ArrayList<>();
        switch (v) {
            case LIVE -> mom.forEach(s -> out.add(s.symbol()));
            case CONTROL -> calm.forEach(s -> out.add(s.symbol()));
            case A -> {
                List<Scored> head = new ArrayList<>(mom.subList(0, Math.min(FIRST_CUT, mom.size())));
                head.sort(Comparator.comparingDouble(Scored::vol));
                head.forEach(s -> out.add(s.symbol()));
                mom.subList(head.size(), mom.size()).forEach(s -> out.add(s.symbol()));
            }
            case B -> {
                Map<String, Integer> momRank = new HashMap<>();
                Map<String, Integer> volRank = new HashMap<>();
                for (int k = 0; k < mom.size(); k++) {
                    momRank.put(mom.get(k).symbol(), k);
                    volRank.put(calm.get(k).symbol(), k);
                }
                mom.stream()
                        .sorted(Comparator.<Scored>comparingInt(s -> momRank.get(s.symbol()) + volRank.get(s.symbol()))
                                .thenComparingInt(s -> momRank.get(s.symbol())))
                        .forEach(s -> out.add(s.symbol()));
            }
        }
        return out;
    }

    /** One variant over one period. */
    record Result(Variant variant, TreeMap<LocalDate, Double> months, double cagrPct, double maxDdPct,
                  double worstMonthPct, double buysPerYear, double avgDaysHeld, int cashMonths) {
    }

    /**
     * The monthly portfolio (as SwingBacktestService.momentumPortfolio for the live rule:
     * equal weight, costs on the slot, next-open fills, idle cash at the liquid-fund yield).
     * {@code calendar} is NIFTY's sessions in the period; {@code nifty} has a year before it.
     */
    static Result simulate(Variant v, Map<String, Series> data, LocalDate[] calendar, Series nifty) {
        List<Integer> rebalance = new ArrayList<>();
        for (int d = 1; d < calendar.length; d++) {
            if (calendar[d].getMonthValue() != calendar[d - 1].getMonthValue()) {
                rebalance.add(d);
            }
        }
        Map<LocalDate, Set<String>> liquid = new HashMap<>();
        TreeMap<LocalDate, Double> months = new TreeMap<>();
        double equity = CAPITAL;
        List<String> held = new ArrayList<>();
        Map<String, LocalDate> boughtOn = new HashMap<>();
        int buys = 0, sells = 0, cash = 0;
        long heldDays = 0;
        for (int k = 0; k + 1 < rebalance.size(); k++) {
            int r = rebalance.get(k);
            int r2 = rebalance.get(k + 1);
            LocalDate signalDay = calendar[r - 1];
            List<String> target = List.of();
            if (SwingBacktestService.aboveAverage(nifty, signalDay, REGIME_SMA)) {
                Set<String> top = liquid.computeIfAbsent(signalDay,
                        day -> SwingBacktestService.topByTurnover(data.values(), day, LIQUID_RANK));
                List<Scored> scored = new ArrayList<>();
                for (Series s : data.values()) {
                    int i = s.indexOf(signalDay);
                    if (i < VOL_WINDOW + 1 || s.indexOf(calendar[r]) < 0 || s.close()[i] < 50
                            || !top.contains(s.symbol())) {
                        continue;
                    }
                    scored.add(new Scored(s.symbol(), SwingBacktestService.momentumScore(s, i, LOOKBACK, SKIP, true),
                            volatility(s, i, VOL_WINDOW)));
                }
                target = SwingBacktestService.bufferedList(ranking(v, scored), held, TOP, HOLD_RANK);
            } else {
                cash++;
            }
            double start = equity;
            double slot = equity / TOP;
            double costs = 0;
            for (String sym : held) {
                if (!target.contains(sym)) {
                    costs += DeliveryCharges.sellSide(slot, 0) + slot * SLIPPAGE_PCT / 100;
                    LocalDate b = boughtOn.remove(sym);
                    if (b != null) {
                        sells++;
                        heldDays += ChronoUnit.DAYS.between(b, calendar[r]);
                    }
                }
            }
            for (String sym : target) {
                if (!held.contains(sym)) {
                    costs += DeliveryCharges.buySide(slot, 0) + slot * SLIPPAGE_PCT / 100;
                    buys++;
                    boughtOn.put(sym, calendar[r]);
                }
            }
            double invested = 0, value = 0;
            for (String sym : target) {
                Series s = data.get(sym);
                int a = s.indexOf(calendar[r]);
                int b = s.indexOf(calendar[r2]);
                invested += slot;
                value += a >= 0 && b >= 0 ? slot * s.open()[b] / s.open()[a] : slot;
            }
            long days = ChronoUnit.DAYS.between(calendar[r], calendar[r2]);
            double interest = (start - invested) * CASH_YIELD / 100 * days / 365;
            equity = equity - invested + value - costs + interest;
            months.put(calendar[r], equity / start - 1);
            held = target;
        }
        return metrics(v, months, rebalance.isEmpty() ? null : calendar[rebalance.get(rebalance.size() - 1)],
                buys, sells, heldDays, cash);
    }

    static Result metrics(Variant v, TreeMap<LocalDate, Double> months, LocalDate end, int buys, int sells,
                          long heldDays, int cash) {
        if (months.isEmpty() || end == null) {
            return new Result(v, months, 0, 0, 0, 0, 0, cash);
        }
        double eq = 1, peak = 1, dd = 0, worst = 0;
        for (double m : months.values()) {
            eq *= 1 + m;
            peak = Math.max(peak, eq);
            dd = Math.max(dd, (peak - eq) / peak);
            worst = Math.min(worst, m);
        }
        double years = ChronoUnit.DAYS.between(months.firstKey(), end) / 365.25;
        double cagr = years > 0 ? Math.pow(eq, 1 / years) - 1 : 0;
        return new Result(v, months, cagr * 100, dd * 100, worst * 100, years > 0 ? buys / years : 0,
                sells > 0 ? (double) heldDays / sells : 0, cash);
    }

    /** Correlation of two variants' monthly returns over their common months. */
    static double correlation(TreeMap<LocalDate, Double> x, TreeMap<LocalDate, Double> y) {
        List<double[]> pairs = new ArrayList<>();
        x.forEach((d, a) -> {
            Double b = y.get(d);
            if (b != null) {
                pairs.add(new double[]{a, b});
            }
        });
        int n = pairs.size();
        if (n < 3) {
            return Double.NaN;
        }
        double ma = pairs.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double mb = pairs.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double sab = 0, saa = 0, sbb = 0;
        for (double[] p : pairs) {
            sab += (p[0] - ma) * (p[1] - mb);
            saa += (p[0] - ma) * (p[0] - ma);
            sbb += (p[1] - mb) * (p[1] - mb);
        }
        return saa > 0 && sbb > 0 ? sab / Math.sqrt(saa * sbb) : Double.NaN;
    }

    /** Pure: the pre-registered rule for one candidate in one period, against LIVE. */
    static boolean passes(Result candidate, Result live) {
        boolean calmer = candidate.maxDdPct() <= live.maxDdPct() - 3 && candidate.cagrPct() >= live.cagrPct() - 2;
        boolean richer = candidate.cagrPct() > live.cagrPct() && candidate.maxDdPct() <= live.maxDdPct() + 2;
        return calmer || richer;
    }

    /** Return gain plus drawdown reduction against LIVE, in points. */
    static double improvement(Result candidate, Result live) {
        return candidate.cagrPct() - live.cagrPct() + live.maxDdPct() - candidate.maxDdPct();
    }

    /** Pure: the decision over both periods (each map: variant -> result). */
    static String decision(Map<Variant, Result> recent, Map<Variant, Result> older) {
        Variant best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        StringBuilder why = new StringBuilder();
        for (Variant v : List.of(Variant.A, Variant.B)) {
            Result r = recent.get(v), o = older.get(v);
            if (r == null || o == null) {
                continue;
            }
            boolean ok = passes(r, recent.get(Variant.LIVE)) && passes(o, older.get(Variant.LIVE));
            double score = Math.min(improvement(r, recent.get(Variant.LIVE)), improvement(o, older.get(Variant.LIVE)));
            why.append(String.format("  %s: recent %s, older %s, smaller improvement %+.1f points%n", v.name(),
                    passes(r, recent.get(Variant.LIVE)) ? "pass" : "fail",
                    passes(o, older.get(Variant.LIVE)) ? "pass" : "fail", score));
            if (ok && score > bestScore) {
                best = v;
                bestScore = score;
            }
        }
        return (best == null ? "DECISION  NO variant passed: the idea is dropped (no paper book, no more variants)"
                : "DECISION  " + best.label + " PASSES: it gets its own Rs 1 lakh paper book next to the live one")
                + "\n" + why;
    }

    // ------------------------------------------------------------------ run

    String run() {
        Map<Variant, Result> recent = period("recent", RECENT_TO);
        Map<Variant, Result> older = period("older", OLDER_TO);
        StringBuilder out = new StringBuilder();
        out.append("MOMENTUM + LOW VOL STUDY (separate strategy, research only) | ").append(LocalDateTime.now(IST)
                .withNano(0)).append("\n");
        out.append("Same as live: top 200 by turnover, no funds, price >= 50, monthly, NIFTY>100DMA else cash 6.5%,\n"
                + "Rs 0 brokerage, 0.10% slippage, delivery charges, top 30 hold to rank 40. Low vol = 1-year daily volatility.\n"
                + "Pass (both periods, vs LIVE): DD >= 3 points lower and %/yr at most 2 lower, OR %/yr higher and DD at most 2 worse.\n\n");
        out.append(render("RECENT (5 years to " + RECENT_TO + ")", recent));
        out.append(render("OLDER (5 years to " + OLDER_TO + ")", older));
        out.append(decision(recent, older));
        return out.toString();
    }

    private Map<Variant, Result> period(String name, LocalDate to) {
        LocalDate from = to.minusYears(YEARS);
        LocalDate fetchFrom = from.minusYears(1).minusDays(20);
        List<String> universe = SwingBacktestService.stockUniverse(jdbc, LocalDate.now(IST).minusDays(30), UNIVERSE);
        status.set("RUNNING | " + name + ": fetching " + universe.size() + " symbols " + fetchFrom + " .. " + to);
        Map<String, Series> data = new ConcurrentHashMap<>();
        List<String> symbols = new ArrayList<>(universe);
        symbols.add("NIFTY");
        Flux.fromIterable(symbols)
                .delayElements(Duration.ofMillis(1000L / Math.max(1, requestsPerSecond)))
                .flatMap(symbol -> stockService.get()
                        .uri(b -> b.path("/api/v1/stocks/{symbol}/history").queryParam("interval", "day")
                                .queryParam("from", fetchFrom).queryParam("to", to).build(symbol))
                        .retrieve().bodyToMono(HistoricalCandleResponse.class)
                        .timeout(Duration.ofSeconds(30))
                        .map(r -> slim(SwingBacktestService.toSeries(symbol, r.getCandles())))
                        .onErrorResume(e -> Mono.empty()), 3)
                .filter(s -> s.size() > 120)
                .doOnNext(s -> data.put(s.symbol(), s))
                .blockLast(Duration.ofMinutes(40));
        Series nifty = data.remove("NIFTY");
        if (nifty == null) {
            throw new IllegalStateException("No NIFTY daily history for the " + name + " period");
        }
        LocalDate[] calendar = SwingBacktestService.since(nifty, from).date();
        status.set("RUNNING | " + name + ": simulating " + data.size() + " stocks over " + calendar.length + " sessions");
        Map<Variant, Result> out = new LinkedHashMap<>();
        for (Variant v : Variant.values()) {
            out.put(v, simulate(v, data, calendar, nifty));
        }
        log.info("Low-vol momentum {}: {} stocks, {} sessions", name, data.size(), calendar.length);
        return out;
    }

    /** High and low are not used: share the close array to halve the memory of 1,500 six-year series. */
    private static Series slim(Series s) {
        return new Series(s.symbol(), s.date(), s.open(), s.close(), s.close(), s.close(), s.volume());
    }

    static String render(String title, Map<Variant, Result> results) {
        Result live = results.get(Variant.LIVE);
        StringBuilder out = new StringBuilder(title).append("\n");
        out.append(String.format("  %-40s %7s %7s %8s %7s %8s %6s %8s%n", "variant", "%/yr", "maxDD", "worst m",
                "buys/y", "days hld", "cash m", "corr LIVE"));
        for (Result r : results.values()) {
            out.append(String.format("  %-40s %+7.1f %7.1f %+8.1f %7.0f %8.0f %6d %8.2f%n", r.variant().label,
                    r.cagrPct(), r.maxDdPct(), r.worstMonthPct(), r.buysPerYear(), r.avgDaysHeld(), r.cashMonths(),
                    correlation(r.months(), live.months())));
        }
        return out.append("\n").toString();
    }
}
