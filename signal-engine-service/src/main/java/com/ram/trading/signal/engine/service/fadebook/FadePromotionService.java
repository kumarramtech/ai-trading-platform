package com.ram.trading.signal.engine.service.fadebook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FADE's promotion test, agreed 2026-11-11: the paper book (rules unchanged since 2026-11-04)
 * earns a small live pilot only if, over the {@value #MIN_SESSIONS} sessions from
 * {@link #TEST_SINCE}, ALL of these hold:
 * <ol>
 *   <li>net after charges at least +{@value #MIN_BPS} bps per short, t at least {@value #MIN_T};</li>
 *   <li>both halves of the sessions net positive;</li>
 *   <li>NIFTY-hedged net positive: each short plus a long NIFTY of the same size from entry to
 *       exit, so the profit is stock picking, not the market falling after 09:45;</li>
 *   <li>worst day better than Rs {@value #WORST_DAY}.</li>
 * </ol>
 * PASSED: live pilot, 1-2 shorts of Rs 10-20k for 20 sessions, real fills against paper.
 * FAILED: FADE stops. Never change these numbers after seeing data. Read-only.
 */
@Service
public class FadePromotionService {

    /**
     * The first session counted: the day after the rules were fixed, so the test sees only data
     * that came after them (the Nov 4-11 sessions were known when the rules were written).
     * Decided once on 2026-11-11; never move it again.
     */
    static final LocalDate TEST_SINCE = LocalDate.of(2026, 11, 12);
    static final int MIN_SESSIONS = 60;
    static final double MIN_BPS = 10;
    static final double MIN_T = 2;
    static final double WORST_DAY = -5000;

    private final JdbcTemplate jdbc;

    public FadePromotionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One closed short: net bps of its notional, hedged bps (null when NIFTY is unknown), net Rs. */
    public record Row(LocalDate day, String symbol, double bps, Double hedgedBps, double net) {
    }

    public record Stats(int n, double meanBps, double t) {
    }

    public record Report(LocalDate since, int sessions, int minSessions, int trades, double totalRs,
                         Stats net, Stats firstHalf, Stats secondHalf, Stats hedged, double worstDay,
                         String verdict, List<String> checks, String next) {
    }

    public Report report() {
        List<Row> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "select trade_date d, symbol s, net_pnl net, entry_price p, quantity q, entry_time ti, exit_time tx "
                        + "from fade_paper_trade where status = 'CLOSED' and net_pnl is not null and trade_date >= ? "
                        + "order by trade_date, id", TEST_SINCE)) {
            double notional = ((Number) r.get("p")).doubleValue() * ((Number) r.get("q")).doubleValue();
            double net = ((Number) r.get("net")).doubleValue();
            double bps = notional > 0 ? net / notional * 10000 : 0;
            Double in = niftyAt(toTime(r.get("ti"))), out = niftyAt(toTime(r.get("tx")));
            // The short's net plus a long NIFTY of the same notional (its own costs ignored).
            Double hedged = in == null || out == null ? null : bps + (out / in - 1) * 10000;
            rows.add(new Row(toDate(r.get("d")), (String) r.get("s"), bps, hedged, net));
        }
        return evaluate(rows);
    }

    /** Pure: statistics and the verdict against the fixed rules. */
    static Report evaluate(List<Row> rows) {
        TreeMap<LocalDate, Double> byDay = new TreeMap<>();
        rows.forEach(r -> byDay.merge(r.day(), r.net(), Double::sum));
        int sessions = byDay.size();
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        LocalDate split = sessions == 0 ? null : days.get(sessions / 2);
        Stats all = stats(rows.stream().map(Row::bps).toList());
        Stats h1 = stats(rows.stream().filter(r -> r.day().isBefore(split)).map(Row::bps).toList());
        Stats h2 = stats(rows.stream().filter(r -> !r.day().isBefore(split)).map(Row::bps).toList());
        Stats hedged = stats(rows.stream().filter(r -> r.hedgedBps() != null).map(Row::hedgedBps).toList());
        double worst = byDay.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double total = rows.stream().mapToDouble(Row::net).sum();

        List<String> checks = new ArrayList<>();
        check(checks, all.meanBps() >= MIN_BPS, String.format("net %+.1f bps per short (need >= +%.0f)", all.meanBps(), MIN_BPS));
        check(checks, all.t() >= MIN_T, String.format("t %.2f (need >= %.1f)", all.t(), MIN_T));
        check(checks, h1.n() > 0 && h1.meanBps() > 0 && h2.n() > 0 && h2.meanBps() > 0,
                String.format("halves %+.1f / %+.1f bps (need both > 0)", h1.meanBps(), h2.meanBps()));
        check(checks, hedged.n() > 0 && hedged.meanBps() > 0,
                String.format("NIFTY-hedged %+.1f bps (need > 0: stock picking, not market direction)", hedged.meanBps()));
        check(checks, worst > WORST_DAY, String.format("worst day Rs %,.0f (need better than Rs %,.0f)", worst, WORST_DAY));

        String verdict, next;
        if (sessions < MIN_SESSIONS) {
            verdict = "COLLECTING " + sessions + "/" + MIN_SESSIONS + " sessions";
            next = "No decision before " + MIN_SESSIONS + " sessions; the checks show the running state only.";
        } else if (checks.stream().allMatch(c -> c.startsWith("PASS"))) {
            verdict = "PASSED";
            next = "Eligible for a live pilot: 1-2 shorts of Rs 10-20k for 20 sessions, real fills compared with paper.";
        } else {
            verdict = "FAILED";
            next = "Stop FADE (trading.fade-book.enabled=false); put the effort into monthly momentum.";
        }
        return new Report(TEST_SINCE, sessions, MIN_SESSIONS, rows.size(), total, all, h1, h2, hedged, worst,
                verdict, checks, next);
    }

    static Stats stats(List<Double> v) {
        int n = v.size();
        if (n == 0) {
            return new Stats(0, 0, 0);
        }
        double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = n < 2 ? 0 : v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (n - 1);
        return new Stats(n, mean, var > 0 ? mean / Math.sqrt(var / n) : 0);
    }

    private static void check(List<String> checks, boolean ok, String text) {
        checks.add((ok ? "PASS " : "FAIL ") + text);
    }

    /** One line for the daily FADE Slack message. */
    public String line() {
        try {
            Report r = report();
            long passing = r.checks().stream().filter(c -> c.startsWith("PASS")).count();
            return String.format("%nPromotion test (fixed 2026-11-11): %s | net %+.1f bps, t %.2f, hedged %+.1f bps, "
                            + "worst day Rs %,.0f | %d of %d checks passing%n", r.verdict(), r.net().meanBps(), r.net().t(),
                    r.hedged().meanBps(), r.worstDay(), passing, r.checks().size());
        } catch (Exception ex) {
            return "";
        }
    }

    public static String toText(Report r) {
        StringBuilder out = new StringBuilder();
        out.append("FADE PROMOTION TEST (paper only; rules fixed 2026-11-11, sessions counted from ")
                .append(r.since()).append(")\n");
        out.append(String.format("  %s | %d shorts, Rs %+,.0f net%n", r.verdict(), r.trades(), r.totalRs()));
        r.checks().forEach(c -> out.append("  ").append(c).append("\n"));
        out.append("  ").append(r.next()).append("\n");
        return out.toString();
    }

    /** NIFTY's last minute close at or before {@code t}, on that day. */
    private Double niftyAt(LocalDateTime t) {
        if (t == null) {
            return null;
        }
        List<Double> v = jdbc.queryForList("select close_price from minute_candle where symbol = 'NIFTY' and "
                        + "candle_minute >= ? and candle_minute <= ? and close_price > 0 order by candle_minute desc limit 1",
                Double.class, t.toLocalDate().atStartOfDay(), t);
        return v.isEmpty() ? null : v.get(0);
    }

    private static LocalDate toDate(Object o) {
        return o instanceof java.sql.Date d ? d.toLocalDate()
                : o instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    private static LocalDateTime toTime(Object o) {
        return o == null ? null : o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime()
                : o instanceof LocalDateTime t ? t : LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
    }
}
