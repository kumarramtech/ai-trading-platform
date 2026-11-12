package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "ETF Trend": a separate strategy study (pre-registered 2026-11-12 in the strategy memory).
 * Reads only; momentum, FADE and intraday are not changed.
 *
 * Six ETFs that behave differently, 1/6 each: NIFTYBEES, JUNIORBEES, GOLDBEES, MON100,
 * HNGSNGBEES, LTGILTBEES. On the first session of each month an ETF is held when its last
 * close is above its 200-day average, else its sixth earns the liquid-fund yield (6.5%).
 * Fills at that session's close (ETF opens in the history are unreliable), held slots are
 * re-sized to 1/6 when more than 2% off. Costs: 0.10% slippage (0.25% MON100 / HNGSNGBEES),
 * stamp 0.015% on buys, STT 0.001% and Rs 18 DP on sells, exchange 0.003% + GST.
 * Report-only check (never a candidate): 12-month return above zero instead of the average.
 *
 * Pass only if in BOTH halves of Jul 2017 .. Nov 2026: (1) the trend book earns >= 8.5%/yr;
 * (2) a 70% momentum / 30% trend mix (live momentum rule, simulated here) has a higher
 * return / max DD ratio than momentum alone; (3) the mix's %/yr is at most 2 points lower.
 */
@Service
@Slf4j
public class EtfTrendStudy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final List<String> ETFS = List.of("NIFTYBEES", "JUNIORBEES", "GOLDBEES", "MON100", "HNGSNGBEES", "LTGILTBEES");
    static final List<String> WIDE_SPREAD = List.of("MON100", "HNGSNGBEES");
    static final LocalDate START = LocalDate.of(2017, 7, 1);
    static final LocalDate FETCH_FROM = LocalDate.of(2016, 1, 1);
    static final LocalDate CHUNK_SPLIT = LocalDate.of(2021, 1, 1);
    static final int SMA = 200;
    static final double CASH_YIELD = 6.5;
    static final double CAPITAL = 100000;
    static final double MIX_MOMENTUM = 0.7;
    static final double MIN_TREND_CAGR = 8.5;
    static final double MAX_MIX_GIVE_UP = 2;
    static final int UNIVERSE = 1500;

    enum Signal { SMA200, TWELVE_MONTH }

    private final JdbcTemplate jdbc;
    private final WebClient stockService;
    private final AtomicReference<String> status = new AtomicReference<>("not run yet");

    @Value("${trading.replay.requests-per-second:6}")
    private int requestsPerSecond = 6;

    public EtfTrendStudy(JdbcTemplate jdbc, WebClient.Builder builder,
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
                    log.info("ETF TREND STUDY DONE\n{}", report);
                }, error -> {
                    log.error("ETF TREND STUDY FAILED", error);
                    status.set("FAILED | " + error.getMessage());
                });
        return true;
    }

    // ------------------------------------------------------------------ pure parts

    /** Index of the last session on or before {@code day}; -1 if none. */
    static int onOrBefore(Series s, LocalDate day) {
        int i = java.util.Arrays.binarySearch(s.date(), day);
        return i >= 0 ? i : -i - 2;
    }

    /** Index of the first session on or after {@code day} within 5 days; -1 if none. */
    static int onOrAfter(Series s, LocalDate day) {
        int i = java.util.Arrays.binarySearch(s.date(), day);
        int k = i >= 0 ? i : -i - 1;
        return k < s.size() && !s.date()[k].isAfter(day.plusDays(5)) ? k : -1;
    }

    /** Pure: the trend signal on {@code signalDay}'s close; false while not known yet. */
    static boolean inTrend(Series s, LocalDate signalDay, Signal signal) {
        int i = onOrBefore(s, signalDay);
        if (i < 0 || ChronoUnit.DAYS.between(s.date()[i], signalDay) > 7) {
            return false;
        }
        return switch (signal) {
            case SMA200 -> i >= SMA - 1 && s.close()[i] > SwingBacktestService.sma(s.close(), i, SMA);
            case TWELVE_MONTH -> i >= 252 && s.close()[i] > s.close()[i - 252];
        };
    }

    static double buyCost(String etf, double value) {
        return value * (slippage(etf) + 0.00015 + 0.00003 * 1.18);
    }

    static double sellCost(String etf, double value) {
        return value * (slippage(etf) + 0.00001 + 0.00003 * 1.18) + 18;
    }

    private static double slippage(String etf) {
        return WIDE_SPREAD.contains(etf) ? 0.0025 : 0.0010;
    }

    /** One run of the trend book: monthly returns keyed by the rebalance session, and turnover. */
    record Book(TreeMap<LocalDate, Double> months, int entries, int exits, double avgDaysHeld, double avgInvestedPct) {
    }

    /**
     * Pure simulation. {@code calendar}: NIFTY's sessions from START; rebalances on the first
     * session of each month, signal on the session before. {@code alwaysIn} = the equal-weight
     * buy-and-hold benchmark (same costs and re-sizing).
     */
    static Book simulate(Map<String, Series> etfs, LocalDate[] calendar, Signal signal, boolean alwaysIn) {
        List<Integer> rebalance = new ArrayList<>();
        for (int d = 1; d < calendar.length; d++) {
            if (calendar[d].getMonthValue() != calendar[d - 1].getMonthValue()) {
                rebalance.add(d);
            }
        }
        TreeMap<LocalDate, Double> months = new TreeMap<>();
        Map<String, Double> value = new HashMap<>();
        Map<String, LocalDate> since = new HashMap<>();
        double cash = CAPITAL;
        int entries = 0, exits = 0;
        long heldDays = 0;
        double investedSum = 0;
        for (int k = 0; k + 1 < rebalance.size(); k++) {
            LocalDate day = calendar[rebalance.get(k)];
            LocalDate next = calendar[rebalance.get(k + 1)];
            LocalDate signalDay = calendar[rebalance.get(k) - 1];
            double equity = cash + value.values().stream().mapToDouble(Double::doubleValue).sum();
            double slot = equity / ETFS.size();
            double invested = 0;
            Map<String, double[]> held = new HashMap<>();   // etf -> {buy close, value}
            for (String etf : ETFS) {
                Series s = etfs.get(etf);
                int a = s == null ? -1 : onOrAfter(s, day);
                double current = value.getOrDefault(etf, 0.0);
                boolean in = a >= 0 && (alwaysIn ? onOrBefore(s, signalDay) >= 0 : inTrend(s, signalDay, signal));
                double target = in ? slot : 0;
                if (target == 0 ? current > 0 : Math.abs(target - current) > 0.02 * slot) {
                    if (target > current) {
                        cash -= target - current + buyCost(etf, target - current);
                    } else {
                        cash += current - target - sellCost(etf, current - target);
                    }
                    current = target;
                }
                if (current > 0 && a >= 0) {
                    held.put(etf, new double[]{s.close()[a], current});
                    invested += current;
                    if (!since.containsKey(etf)) {
                        since.put(etf, day);
                        entries++;
                    }
                } else {
                    if (current > 0) {
                        cash += current;   // no price this month: treat as cash (rare)
                    }
                    LocalDate b = since.remove(etf);
                    if (b != null) {
                        exits++;
                        heldDays += ChronoUnit.DAYS.between(b, day);
                    }
                }
            }
            investedSum += equity > 0 ? invested / equity : 0;
            // Through the month: holdings move with the close, cash earns the liquid-fund yield.
            value.clear();
            for (Map.Entry<String, double[]> h : held.entrySet()) {
                Series s = etfs.get(h.getKey());
                int b = onOrAfter(s, next);
                if (b < 0) {
                    b = onOrBefore(s, next);
                }
                value.put(h.getKey(), h.getValue()[1] * s.close()[b] / h.getValue()[0]);
            }
            cash += cash * CASH_YIELD / 100 * ChronoUnit.DAYS.between(day, next) / 365;
            double end = cash + value.values().stream().mapToDouble(Double::doubleValue).sum();
            months.put(day, end / equity - 1);
        }
        int n = Math.max(1, months.size());
        return new Book(months, entries, exits, exits > 0 ? (double) heldDays / exits : 0, investedSum / n * 100);
    }

    /** Return %/yr, max DD %, worst month % of a monthly series. */
    record Stats(double cagrPct, double maxDdPct, double worstMonthPct) {
        double ratio() {
            return maxDdPct > 0 ? cagrPct / maxDdPct : cagrPct > 0 ? Double.POSITIVE_INFINITY : 0;
        }
    }

    static Stats stats(TreeMap<LocalDate, Double> months) {
        if (months.size() < 2) {
            return new Stats(0, 0, 0);
        }
        double eq = 1, peak = 1, dd = 0, worst = 0;
        for (double m : months.values()) {
            eq *= 1 + m;
            peak = Math.max(peak, eq);
            dd = Math.max(dd, (peak - eq) / peak);
            worst = Math.min(worst, m);
        }
        double years = months.size() / 12.0;
        return new Stats((Math.pow(eq, 1 / years) - 1) * 100, dd * 100, worst * 100);
    }

    /** The mix rebalanced monthly between the two books (book-level switching costs ignored). */
    static TreeMap<LocalDate, Double> mix(TreeMap<LocalDate, Double> momentum, TreeMap<LocalDate, Double> trend) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        momentum.forEach((d, m) -> {
            Double t = trend.get(d);
            if (t != null) {
                out.put(d, MIX_MOMENTUM * m + (1 - MIX_MOMENTUM) * t);
            }
        });
        return out;
    }

    static TreeMap<LocalDate, Double> part(TreeMap<LocalDate, Double> m, boolean first) {
        List<LocalDate> keys = new ArrayList<>(m.keySet());
        LocalDate cut = keys.get(keys.size() / 2);
        return new TreeMap<>(first ? m.headMap(cut, false) : m.tailMap(cut, true));
    }

    /** Pure: the pre-registered rule in one half. */
    static boolean passes(Stats trend, Stats momentum, Stats mix) {
        return trend.cagrPct() >= MIN_TREND_CAGR && mix.ratio() > momentum.ratio()
                && mix.cagrPct() >= momentum.cagrPct() - MAX_MIX_GIVE_UP;
    }

    // ------------------------------------------------------------------ run

    String run() {
        LocalDate to = LocalDate.now(IST).minusDays(1);
        status.set("RUNNING | fetching the 6 ETFs and NIFTY");
        Map<String, Series> etfs = new HashMap<>();
        for (String etf : ETFS) {
            Series s = fetch(etf, FETCH_FROM, to);
            if (s != null) {
                etfs.put(etf, s);
            }
        }
        Series nifty = fetch("NIFTY", FETCH_FROM, to);
        if (nifty == null) {
            throw new IllegalStateException("No NIFTY daily history");
        }
        LocalDate[] calendar = SwingBacktestService.since(nifty, START).date();

        List<String> universe = SwingBacktestService.stockUniverse(jdbc, LocalDate.now(IST).minusDays(30), UNIVERSE);
        status.set("RUNNING | fetching " + universe.size() + " stocks for the momentum comparison (2 chunks each)");
        Map<String, Series> stocks = new ConcurrentHashMap<>();
        Flux.fromIterable(universe)
                .delayElements(Duration.ofMillis(2000L / Math.max(1, requestsPerSecond)))
                .flatMap(symbol -> Mono.fromCallable(() -> fetch(symbol, START.minusYears(1).minusDays(30), to))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(e -> Mono.empty()), 3)
                .filter(s -> s != null && s.size() > 120)
                .doOnNext(s -> stocks.put(s.symbol(), s))
                .blockLast(Duration.ofMinutes(60));
        status.set("RUNNING | simulating momentum over " + stocks.size() + " stocks and the ETF books");
        TreeMap<LocalDate, Double> momentum = LowVolMomentumStudy.simulate(LowVolMomentumStudy.Variant.LIVE, stocks,
                calendar, nifty).months();
        int stockCount = stocks.size();
        stocks.clear();

        Book trend = simulate(etfs, calendar, Signal.SMA200, false);
        Book twelve = simulate(etfs, calendar, Signal.TWELVE_MONTH, false);
        Book hold = simulate(etfs, calendar, Signal.SMA200, true);
        TreeMap<LocalDate, Double> mixed = mix(momentum, trend.months());

        StringBuilder out = new StringBuilder();
        out.append("ETF TREND STUDY (separate strategy, research only) | ").append(LocalDateTime.now(IST).withNano(0))
                .append("\n6 ETFs at 1/6: ").append(String.join(", ", ETFS)).append(" (loaded: ")
                .append(String.join(", ", etfs.keySet())).append(")\n");
        out.append("Rule: hold an ETF while its close is above its 200-day average (checked monthly), else that sixth in the "
                + "liquid fund at 6.5%.\nMomentum = the live rule over ").append(stockCount)
                .append(" stocks (same simulator as the low-vol study). Mix = 70% momentum / 30% trend, monthly.\n");
        out.append(String.format("Pass in BOTH halves: trend >= %.1f%%/yr, mix return/DD above momentum's, "
                + "mix at most %.0f points/yr below momentum.%n%n", MIN_TREND_CAGR, MAX_MIX_GIVE_UP));
        Map<String, TreeMap<LocalDate, Double>> rows = new LinkedHashMap<>();
        rows.put("ETF TREND (200-day average)", trend.months());
        rows.put("report only: 12-month return > 0", twelve.months());
        rows.put("6 ETFs buy and hold (equal weight)", hold.months());
        rows.put("NIFTYBEES buy and hold", single(etfs.get("NIFTYBEES"), calendar));
        rows.put("MOMENTUM (live rule)", momentum);
        rows.put("MIX 70% momentum / 30% trend", mixed);
        boolean[] pass = new boolean[2];
        String[] names = {"FULL", "HALF 1", "HALF 2"};
        for (int h = 0; h < 3; h++) {
            out.append(names[h]).append(h == 0 ? "" : h == 1 ? " (first half of the months)" : " (second half)").append("\n");
            out.append(String.format("  %-38s %7s %7s %8s %7s%n", "book", "%/yr", "maxDD", "worst m", "ret/DD"));
            Map<String, Stats> st = new LinkedHashMap<>();
            for (Map.Entry<String, TreeMap<LocalDate, Double>> r : rows.entrySet()) {
                TreeMap<LocalDate, Double> m = h == 0 ? r.getValue() : part(r.getValue(), h == 1);
                Stats s = stats(m);
                st.put(r.getKey(), s);
                out.append(String.format("  %-38s %+7.1f %7.1f %+8.1f %7.2f%n", r.getKey(), s.cagrPct(), s.maxDdPct(),
                        s.worstMonthPct(), s.ratio()));
            }
            if (h > 0) {
                pass[h - 1] = passes(st.get("ETF TREND (200-day average)"), st.get("MOMENTUM (live rule)"),
                        st.get("MIX 70% momentum / 30% trend"));
                out.append("  -> ").append(pass[h - 1] ? "PASS" : "FAIL").append("\n");
            }
            out.append("\n");
        }
        out.append(String.format("Trend book: %d entries, %d exits, held %.0f days on average, %.0f%% invested on average; "
                        + "correlation with momentum %.2f%n", trend.entries(), trend.exits(), trend.avgDaysHeld(),
                trend.avgInvestedPct(), LowVolMomentumStudy.correlation(trend.months(), momentum)));
        out.append(pass[0] && pass[1]
                ? "DECISION  ETF TREND PASSES: it gets its own Rs 1 lakh paper book (separate tables, Slack, endpoint)\n"
                : "DECISION  ETF TREND FAILS: dropped, no paper book and no more variants\n");
        return out.toString();
    }

    private static TreeMap<LocalDate, Double> single(Series s, LocalDate[] calendar) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        if (s == null) {
            return out;
        }
        LocalDate prev = null;
        for (int d = 1; d < calendar.length; d++) {
            if (calendar[d].getMonthValue() != calendar[d - 1].getMonthValue()) {
                if (prev != null) {
                    int a = onOrAfter(s, prev), b = onOrAfter(s, calendar[d]);
                    if (a >= 0 && b >= 0) {
                        out.put(prev, s.close()[b] / s.close()[a] - 1);
                    }
                }
                prev = calendar[d];
            }
        }
        return out;
    }

    /** Daily history in two calls (one long range comes back empty), merged by date. */
    private Series fetch(String symbol, LocalDate from, LocalDate to) {
        List<Series> parts = new ArrayList<>();
        LocalDate[][] ranges = from.isBefore(CHUNK_SPLIT)
                ? new LocalDate[][]{{from, CHUNK_SPLIT.minusDays(1)}, {CHUNK_SPLIT, to}}
                : new LocalDate[][]{{from, to}};
        for (LocalDate[] r : ranges) {
            try {
                HistoricalCandleResponse resp = stockService.get()
                        .uri(b -> b.path("/api/v1/stocks/{symbol}/history").queryParam("interval", "day")
                                .queryParam("from", r[0]).queryParam("to", r[1]).build(symbol))
                        .retrieve().bodyToMono(HistoricalCandleResponse.class)
                        .block(Duration.ofSeconds(40));
                if (resp != null) {
                    parts.add(SwingBacktestService.toSeries(symbol, resp.getCandles()));
                }
            } catch (Exception ex) {
                log.warn("ETF trend: no history for {} {}..{}: {}", symbol, r[0], r[1], ex.getMessage());
            }
        }
        Series merged = merge(symbol, parts);
        return merged.size() == 0 ? null : merged;
    }

    /** Pure: concatenates series parts by date (later duplicates dropped); high/low share the close. */
    static Series merge(String symbol, List<Series> parts) {
        TreeMap<LocalDate, double[]> rows = new TreeMap<>();
        for (Series p : parts) {
            for (int i = 0; i < p.size(); i++) {
                rows.putIfAbsent(p.date()[i], new double[]{p.open()[i], p.close()[i], p.volume()[i]});
            }
        }
        int n = rows.size();
        LocalDate[] d = new LocalDate[n];
        double[] o = new double[n], c = new double[n], v = new double[n];
        int i = 0;
        for (Map.Entry<LocalDate, double[]> e : rows.entrySet()) {
            d[i] = e.getKey();
            o[i] = e.getValue()[0];
            c[i] = e.getValue()[1];
            v[i] = e.getValue()[2];
            i++;
        }
        return new Series(symbol, d, o, c, c, c, v);
    }
}
