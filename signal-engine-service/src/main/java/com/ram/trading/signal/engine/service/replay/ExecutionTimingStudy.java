package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.indicator.Candle;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Research study, separate from every strategy (2026-11-11, pre-registered in the strategy
 * memory): WHEN in the day should the monthly momentum orders execute? Reads only; changes
 * nothing. For each of the last N months: the point-in-time momentum ranking on the last
 * session before the month (top 200 by turnover, 6m-1m vol-adjusted, price >= 50, no funds).
 * BUY set = ranks 1-30 (what the book buys and holds); SELL set = ranks 41-80 (what it sells
 * under hold-to-rank 40). On every session of the month, each stock's 1-minute prices give the
 * price at each of 9 options against that day's VWAP: buy cost = price / VWAP - 1 (lower is
 * better), sell cost = VWAP / price - 1 (lower is better), in bps. Per option and side: the
 * mean of daily averages (stocks on one day move together, so the day is the unit), its t
 * against 09:20, both halves of the period, and the 1-minute range as a spread proxy.
 * Switch a side from 09:20 only if an option is >= 5 bps better in both halves with t >= 2.
 */
@Service
@Slf4j
public class ExecutionTimingStudy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime[] TIMES = {LocalTime.of(9, 20), LocalTime.of(9, 30), LocalTime.of(10, 0),
            LocalTime.of(10, 30), LocalTime.of(11, 30), LocalTime.of(13, 0), LocalTime.of(14, 30),
            LocalTime.of(15, 20)};
    static final String[] OPTIONS = {"09:20 (now)", "09:30", "10:00", "10:30", "11:30", "13:00", "14:30", "15:20",
            "split 09:30-11:30"};
    static final double MIN_BPS = 5;
    static final double MIN_T = 2;

    private final JdbcTemplate jdbc;
    private final WebClient stockService;
    private final AtomicReference<String> status = new AtomicReference<>("not run yet");

    @Value("${trading.replay.requests-per-second:6}")
    private int requestsPerSecond = 6;

    public ExecutionTimingStudy(JdbcTemplate jdbc, WebClient.Builder builder,
                                @Value("${stock.service.url}") String stockServiceUrl) {
        this.jdbc = jdbc;
        this.stockService = builder.clone().baseUrl(stockServiceUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024)).build();
    }

    public String status() {
        return status.get();
    }

    public synchronized boolean start(int months) {
        if (status.get().startsWith("RUNNING")) {
            return false;
        }
        status.set("RUNNING | starting " + LocalDateTime.now(IST));
        Mono.fromCallable(() -> run(months))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(report -> {
                    status.set(report);
                    log.info("EXECUTION TIMING DONE\n{}", report);
                }, error -> {
                    log.error("EXECUTION TIMING FAILED", error);
                    status.set("FAILED | " + error.getMessage());
                });
        return true;
    }

    // ------------------------------------------------------------------ pure parts

    /** One 1-minute bar. */
    record Bar(LocalTime t, double close, double volume, double high, double low) {
    }

    /** One stock-day: the price at each option (NaN if missing), the day's VWAP, the range (bps) at each time. */
    record DayPrices(double[] price, double vwap, double[] rangeBps) {
    }

    /** Pure: prices at the 8 times and the 09:30-11:30 split (every 15 minutes), against the day's VWAP. */
    static DayPrices prices(List<Bar> bars) {
        double pv = 0, v = 0;
        for (Bar b : bars) {
            pv += b.close() * b.volume();
            v += b.volume();
        }
        double vwap = v > 0 ? pv / v : Double.NaN;
        double[] price = new double[OPTIONS.length];
        double[] range = new double[TIMES.length];
        java.util.Arrays.fill(price, Double.NaN);
        java.util.Arrays.fill(range, Double.NaN);
        for (int k = 0; k < TIMES.length; k++) {
            Bar b = at(bars, TIMES[k]);
            if (b != null) {
                price[k] = b.close();
                range[k] = b.close() > 0 ? (b.high() - b.low()) / b.close() * 10000 : Double.NaN;
            }
        }
        double sum = 0;
        int n = 0;
        for (LocalTime t = LocalTime.of(9, 30); !t.isAfter(LocalTime.of(11, 30)); t = t.plusMinutes(15)) {
            Bar b = at(bars, t);
            if (b != null) {
                sum += b.close();
                n++;
            }
        }
        price[OPTIONS.length - 1] = n >= 7 ? sum / n : Double.NaN;
        return new DayPrices(price, vwap, range);
    }

    /** The bar of minute t, else the nearest within 2 minutes after it. */
    static Bar at(List<Bar> bars, LocalTime t) {
        for (Bar b : bars) {
            if (!b.t().isBefore(t) && !b.t().isAfter(t.plusMinutes(2))) {
                return b;
            }
        }
        return null;
    }

    /** Cost in bps for one side at option k (lower is better); NaN when unknown. */
    static double cost(DayPrices d, int k, boolean buy) {
        double p = d.price()[k];
        if (Double.isNaN(p) || Double.isNaN(d.vwap()) || p <= 0 || d.vwap() <= 0) {
            return Double.NaN;
        }
        return (buy ? p / d.vwap() - 1 : d.vwap() / p - 1) * 10000;
    }

    /** Per-day sums for one side: day -> {sum of cost per option..., count per option...}. */
    static final class Side {
        final Map<LocalDate, double[]> days = new TreeMap<>();
        final double[] rangeSum = new double[TIMES.length];
        final int[] rangeN = new int[TIMES.length];

        synchronized void add(LocalDate day, DayPrices d, boolean buy) {
            double[] a = days.computeIfAbsent(day, x -> new double[OPTIONS.length * 2]);
            for (int k = 0; k < OPTIONS.length; k++) {
                double c = cost(d, k, buy);
                if (!Double.isNaN(c) && !Double.isNaN(cost(d, 0, buy))) {
                    a[k] += c;
                    a[OPTIONS.length + k]++;
                }
            }
            for (int k = 0; k < TIMES.length; k++) {
                if (!Double.isNaN(d.rangeBps()[k])) {
                    rangeSum[k] += d.rangeBps()[k];
                    rangeN[k]++;
                }
            }
        }
    }

    /** Result of one option on one side. */
    record Row(String option, int days, double meanBps, double vs0920, double t, double half1, double half2,
               Double rangeBps, boolean passes) {
    }

    /** Pure: per option, the mean of daily means, the gain vs 09:20, t on daily differences, both halves. */
    static List<Row> evaluate(Side side) {
        List<LocalDate> days = new ArrayList<>(side.days.keySet());
        int half = days.size() / 2;
        List<Row> rows = new ArrayList<>();
        for (int k = 0; k < OPTIONS.length; k++) {
            List<Double> mean = new ArrayList<>(), diff = new ArrayList<>(), d1 = new ArrayList<>(), d2 = new ArrayList<>();
            for (int i = 0; i < days.size(); i++) {
                double[] a = side.days.get(days.get(i));
                double n = a[OPTIONS.length + k], n0 = a[OPTIONS.length];
                if (n == 0 || n0 == 0) {
                    continue;
                }
                double m = a[k] / n, gain = a[0] / n0 - m;   // positive = cheaper than 09:20
                mean.add(m);
                diff.add(gain);
                (i < half ? d1 : d2).add(gain);
            }
            double g = avg(diff), t = tStat(diff), h1 = avg(d1), h2 = avg(d2);
            boolean passes = k > 0 && g >= MIN_BPS && t >= MIN_T && h1 > 0 && h2 > 0;
            Double range = k < TIMES.length && side.rangeN[k] > 0 ? side.rangeSum[k] / side.rangeN[k] : null;
            rows.add(new Row(OPTIONS[k], mean.size(), avg(mean), g, t, h1, h2, range, passes));
        }
        return rows;
    }

    static double avg(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    static double tStat(List<Double> v) {
        int n = v.size();
        if (n < 2) {
            return 0;
        }
        double m = avg(v);
        double var = v.stream().mapToDouble(x -> (x - m) * (x - m)).sum() / (n - 1);
        return var > 0 ? m / Math.sqrt(var / n) : 0;
    }

    /** The option a side should switch to (best smaller half among passing), else 09:20. */
    static String decision(List<Row> rows) {
        return rows.stream().filter(Row::passes)
                .max(java.util.Comparator.comparingDouble(r -> Math.min(r.half1(), r.half2())))
                .map(r -> "SWITCH to " + r.option())
                .orElse("KEEP 09:20 (no option passed)");
    }

    // ------------------------------------------------------------------ run

    String run(int months) {
        LocalDate to = LocalDate.now(IST).minusDays(1);
        LocalDate from = to.minusMonths(months).withDayOfMonth(1);
        List<String> universe = SwingBacktestService.stockUniverse(jdbc, LocalDate.now(IST).minusDays(30), 600);
        status.set("RUNNING | daily history for " + universe.size() + " symbols");
        Map<String, Series> daily = new ConcurrentHashMap<>();
        List<String> symbols = new ArrayList<>(universe);
        symbols.add("NIFTY");
        Flux.fromIterable(symbols)
                .delayElements(Duration.ofMillis(1000L / Math.max(1, requestsPerSecond)))
                .flatMap(s -> history(s, "day", from.minusDays(330), to)
                        .map(c -> SwingBacktestService.toSeries(s, c))
                        .filter(x -> x.size() > 0)
                        .doOnNext(x -> daily.put(s, x))
                        .onErrorResume(e -> Mono.empty()), 3)
                .blockLast(Duration.ofMinutes(30));
        Series nifty = daily.remove("NIFTY");
        if (nifty == null) {
            return "FAILED | no NIFTY daily history (broker token or stock-service down?)";
        }

        // Month by month: the ranking on the last session before it, and the month's sessions.
        LocalDate[] cal = nifty.date();
        Map<String, List<LocalDate[]>> buyMonths = new HashMap<>(), sellMonths = new HashMap<>();
        int monthsUsed = 0;
        for (int i = 1; i < cal.length; i++) {
            if (cal[i].isBefore(from) || cal[i].getMonthValue() == cal[i - 1].getMonthValue()) {
                continue;
            }
            int end = i;
            while (end + 1 < cal.length && cal[end + 1].getMonthValue() == cal[i].getMonthValue()) {
                end++;
            }
            List<String> ranked = ranking(daily, cal[i - 1]);
            LocalDate[] span = {cal[i], cal[end]};
            for (int r = 0; r < Math.min(80, ranked.size()); r++) {
                if (r < 30) {
                    buyMonths.computeIfAbsent(ranked.get(r), x -> new ArrayList<>()).add(span);
                } else if (r >= 40) {
                    sellMonths.computeIfAbsent(ranked.get(r), x -> new ArrayList<>()).add(span);
                }
            }
            monthsUsed++;
        }

        Side buys = new Side(), sells = new Side();
        // Upstox returns nothing for a long 1-minute range: fetch each stock-month in 7-day chunks.
        List<Map.Entry<String, LocalDate[]>> jobs = new ArrayList<>();
        buyMonths.forEach((s, spans) -> spans.forEach(sp -> chunks(sp).forEach(c -> jobs.add(Map.entry("B|" + s, c)))));
        sellMonths.forEach((s, spans) -> spans.forEach(sp -> chunks(sp).forEach(c -> jobs.add(Map.entry("S|" + s, c)))));
        status.set("RUNNING | 1-minute history for " + jobs.size() + " stock-months over " + monthsUsed + " months");
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        Flux.fromIterable(jobs)
                .delayElements(Duration.ofMillis(1000L / Math.max(1, requestsPerSecond)))
                .flatMap(job -> {
                    String symbol = job.getKey().substring(2);
                    boolean buy = job.getKey().startsWith("B");
                    return history(symbol, "1minute", job.getValue()[0], job.getValue()[1])
                            .doOnNext(c -> {
                                byDay(c).forEach((day, bars) -> (buy ? buys : sells).add(day, prices(bars), buy));
                                if (done.incrementAndGet() % 50 == 0) {
                                    status.set("RUNNING | " + done.get() + " of " + jobs.size() + " stock-months");
                                }
                            })
                            .onErrorResume(e -> Mono.empty());
                }, 3)
                .blockLast(Duration.ofMinutes(120));

        return report(from, to, monthsUsed, jobs.size(), evaluate(buys), evaluate(sells));
    }

    /** A month's span cut into 7-calendar-day pieces. */
    static List<LocalDate[]> chunks(LocalDate[] span) {
        List<LocalDate[]> out = new ArrayList<>();
        for (LocalDate s = span[0]; !s.isAfter(span[1]); s = s.plusDays(7)) {
            LocalDate e = s.plusDays(6).isAfter(span[1]) ? span[1] : s.plusDays(6);
            out.add(new LocalDate[]{s, e});
        }
        return out;
    }

    /** Fewer sessions than this and the report says INVALID instead of a decision. */
    static final int MIN_DAYS = 150;

    /** Point-in-time momentum ranking on {@code day}, best first (as the live list, without the buffer). */
    private List<String> ranking(Map<String, Series> data, LocalDate day) {
        Set<String> liquid = SwingBacktestService.topByTurnover(data.values(), day, 200);
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Series s : data.values()) {
            int i = s.indexOf(day);
            if (i < 127 || s.close()[i] < 50 || !liquid.contains(s.symbol())) {
                continue;
            }
            ranked.add(Map.entry(s.symbol(), SwingBacktestService.momentumScore(s, i, 126, 21, true)));
        }
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        return ranked.stream().map(Map.Entry::getKey).toList();
    }

    private Mono<List<Candle>> history(String symbol, String interval, LocalDate from, LocalDate to) {
        return stockService.get()
                .uri(b -> b.path("/api/v1/stocks/{symbol}/history").queryParam("interval", interval)
                        .queryParam("from", from).queryParam("to", to).build(symbol))
                .retrieve()
                .bodyToMono(HistoricalCandleResponse.class)
                .timeout(Duration.ofSeconds(60))
                .map(r -> r.getCandles() == null ? List.<Candle>of() : r.getCandles());
    }

    /** 1-minute candles grouped by IST day, session minutes only (09:15-15:29). */
    static Map<LocalDate, List<Bar>> byDay(List<Candle> candles) {
        Map<LocalDate, List<Bar>> out = new LinkedHashMap<>();
        List<Candle> sorted = new ArrayList<>(candles);
        sorted.removeIf(c -> c.getDateTime() == null || c.getClose() == null);
        sorted.sort(java.util.Comparator.comparing(Candle::getDateTime));
        for (Candle c : sorted) {
            LocalDateTime m = c.getDateTime().atZoneSameInstant(IST).toLocalDateTime();
            if (m.toLocalTime().isBefore(LocalTime.of(9, 15)) || m.toLocalTime().isAfter(LocalTime.of(15, 29))) {
                continue;
            }
            double close = c.getClose().doubleValue();
            out.computeIfAbsent(m.toLocalDate(), d -> new ArrayList<>()).add(new Bar(m.toLocalTime(), close,
                    c.getVolume() == null ? 0 : c.getVolume(),
                    c.getHigh() == null ? close : c.getHigh().doubleValue(),
                    c.getLow() == null ? close : c.getLow().doubleValue()));
        }
        return out;
    }

    static String report(LocalDate from, LocalDate to, int months, int stockMonths, List<Row> buy, List<Row> sell) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("EXECUTION TIMING STUDY %s .. %s | %d months, %d stock-months | research only%n",
                from, to, months, stockMonths));
        out.append("Cost vs the day's VWAP in bps (lower is better). Gain = cheaper than 09:20 (+ is better).\n");
        out.append("Pass: gain >= +5 bps, t >= 2 on daily averages, both halves > 0.\n\n");
        section(out, "BUYS (ranks 1-30: what the book buys)", buy);
        section(out, "SELLS (ranks 41-80: what it sells under hold-to-rank 40)", sell);
        int days = Math.min(buy.isEmpty() ? 0 : buy.get(0).days(), sell.isEmpty() ? 0 : sell.get(0).days());
        if (days < MIN_DAYS) {
            out.append("DECISION  INVALID: only ").append(days).append(" sessions with data (need >= ").append(MIN_DAYS)
                    .append("); no conclusion, fix the data and rerun.\n");
            return out.toString();
        }
        out.append("DECISION  buys: ").append(decision(buy)).append(" | sells: ").append(decision(sell)).append("\n");
        out.append("A switch changes only the monthly rebalance time, from a monthly rebalance; no strategy rule changes.\n");
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<Row> rows) {
        out.append(title).append("\n");
        out.append(String.format("  %-18s %5s %9s %9s %7s %8s %8s %9s%n", "option", "days", "cost", "gain", "t",
                "half 1", "half 2", "range"));
        for (Row r : rows) {
            out.append(String.format("  %-18s %5d %+9.1f %+9.1f %7.2f %+8.1f %+8.1f %9s %s%n", r.option(), r.days(),
                    r.meanBps(), r.vs0920(), r.t(), r.half1(), r.half2(),
                    r.rangeBps() == null ? "-" : String.format("%.1f", r.rangeBps()), r.passes() ? "PASS" : ""));
        }
        out.append("\n");
    }
}
