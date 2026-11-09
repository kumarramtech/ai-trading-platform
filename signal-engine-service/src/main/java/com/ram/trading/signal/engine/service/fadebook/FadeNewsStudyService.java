package com.ram.trading.signal.engine.service.fadebook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Report of the FADE news study (see {@link FadeNewsTagger} for the pre-registered
 * hypothesis and pass mark). Read-only: closed FADE shorts joined with their tags.
 * NIFTY up or down day = the day's last NIFTY minute close against the previous
 * session's.
 */
@Service
public class FadeNewsStudyService {

    static final int MIN_SESSIONS = 60;
    static final double MIN_BPS = 15;
    static final double MIN_T = 2;

    private final JdbcTemplate jdbc;

    public FadeNewsStudyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One closed short: net in bps of its notional, its tag, whether NIFTY rose that day (null = unknown). */
    public record Row(LocalDate day, String symbol, String tag, double bps, double net, Boolean niftyUp) {
    }

    public record Group(String name, int n, double meanBps, double t, double totalRs) {
    }

    public record Report(int sessions, int tagged, int excluded, Group noNews, Group withNews, Group catalyst,
                         Group news, Group noNewsUpDays, Group noNewsDownDays, String verdict, List<String> reasons) {
    }

    public Report report() {
        List<Map<String, Object>> raw = jdbc.queryForList(
                "select f.trade_date d, f.symbol s, t.tag tag, f.net_pnl net, f.entry_price p, f.quantity q "
                        + "from fade_paper_trade f join fade_news_tag t on t.trade_id = f.id "
                        + "where f.status = 'CLOSED' and f.net_pnl is not null order by f.trade_date, f.id");
        Set<LocalDate> days = new TreeSet<>();
        raw.forEach(r -> days.add(toDate(r.get("d"))));
        Map<LocalDate, Boolean> up = niftyUp(days);
        List<Row> rows = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            LocalDate d = toDate(r.get("d"));
            double notional = ((Number) r.get("p")).doubleValue() * ((Number) r.get("q")).doubleValue();
            double net = ((Number) r.get("net")).doubleValue();
            rows.add(new Row(d, (String) r.get("s"), (String) r.get("tag"), notional > 0 ? net / notional * 10000 : 0,
                    net, up.get(d)));
        }
        return evaluate(rows);
    }

    /** Pure: groups, statistics and the verdict against the fixed pass mark. */
    static Report evaluate(List<Row> rows) {
        List<Row> in = rows.stream().filter(r -> isStudied(r.tag())).toList();
        int sessions = (int) in.stream().map(Row::day).distinct().count();
        List<Row> none = in.stream().filter(r -> FadeNewsTagger.NO_NEWS.equals(r.tag())).toList();
        List<Row> with = in.stream().filter(r -> !FadeNewsTagger.NO_NEWS.equals(r.tag())).toList();
        Group g0 = group("NO_NEWS", none);
        Group g1 = group("NEWS+CATALYST", with);
        Group cat = group("CATALYST", in.stream().filter(r -> FadeNewsTagger.CATALYST.equals(r.tag())).toList());
        Group nws = group("NEWS", in.stream().filter(r -> FadeNewsTagger.NEWS.equals(r.tag())).toList());
        Group upG = group("NO_NEWS on NIFTY up days", none.stream().filter(r -> Boolean.TRUE.equals(r.niftyUp())).toList());
        Group dnG = group("NO_NEWS on NIFTY down days", none.stream().filter(r -> Boolean.FALSE.equals(r.niftyUp())).toList());

        List<String> reasons = new ArrayList<>();
        String verdict;
        if (sessions < MIN_SESSIONS) {
            verdict = "COLLECTING " + sessions + "/" + MIN_SESSIONS + " sessions - no conclusion yet";
        } else {
            check(reasons, g0.meanBps() >= MIN_BPS, String.format("NO_NEWS net %.1f bps (need >= %.0f)", g0.meanBps(), MIN_BPS));
            check(reasons, g0.t() >= MIN_T, String.format("NO_NEWS t %.2f (need >= %.1f)", g0.t(), MIN_T));
            check(reasons, upG.n() > 0 && upG.meanBps() > 0, String.format("NO_NEWS on up days %.1f bps (need > 0)", upG.meanBps()));
            check(reasons, dnG.n() > 0 && dnG.meanBps() > 0, String.format("NO_NEWS on down days %.1f bps (need > 0)", dnG.meanBps()));
            check(reasons, g0.meanBps() > g1.meanBps(), String.format("NO_NEWS %.1f vs with news %.1f bps (need higher)",
                    g0.meanBps(), g1.meanBps()));
            verdict = reasons.stream().allMatch(s -> s.startsWith("PASS")) ? "PASSED" : "FAILED";
        }
        return new Report(sessions, in.size(), rows.size() - in.size(), g0, g1, cat, nws, upG, dnG, verdict, reasons);
    }

    private static boolean isStudied(String tag) {
        return FadeNewsTagger.NO_NEWS.equals(tag) || FadeNewsTagger.NEWS.equals(tag) || FadeNewsTagger.CATALYST.equals(tag);
    }

    private static void check(List<String> reasons, boolean ok, String text) {
        reasons.add((ok ? "PASS " : "FAIL ") + text);
    }

    static Group group(String name, List<Row> rows) {
        int n = rows.size();
        if (n == 0) {
            return new Group(name, 0, 0, 0, 0);
        }
        double mean = rows.stream().mapToDouble(Row::bps).average().orElse(0);
        double var = n < 2 ? 0 : rows.stream().mapToDouble(r -> (r.bps() - mean) * (r.bps() - mean)).sum() / (n - 1);
        double t = var > 0 ? mean / Math.sqrt(var / n) : 0;
        return new Group(name, n, mean, t, rows.stream().mapToDouble(Row::net).sum());
    }

    private Map<LocalDate, Boolean> niftyUp(Set<LocalDate> days) {
        Map<LocalDate, Boolean> out = new HashMap<>();
        for (LocalDate d : days) {
            Double close = lastClose("candle_minute >= ? and candle_minute < ?", d, d.plusDays(1));
            Double prev = lastClose("candle_minute >= ? and candle_minute < ?", d.minusDays(10), d);
            if (close != null && prev != null) {
                out.put(d, close > prev);
            }
        }
        return out;
    }

    private Double lastClose(String where, LocalDate from, LocalDate to) {
        List<Double> v = jdbc.queryForList("select close_price from minute_candle where symbol = 'NIFTY' and " + where
                + " order by candle_minute desc limit 1", Double.class, from, to);
        return v.isEmpty() ? null : v.get(0);
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return o instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    public static String toText(Report r) {
        StringBuilder out = new StringBuilder();
        out.append("FADE NEWS STUDY (pre-registered 2026-11-09; data collection only, FADE rules unchanged)\n");
        out.append("Hypothesis: shorts on moves WITHOUT news earn more than shorts on moves with news.\n");
        out.append(String.format("Pass mark after >= %d sessions: NO_NEWS >= +%.0f bps net, t >= %.1f, positive on NIFTY up"
                + " and down days, and above the shorts with news.%n%n", MIN_SESSIONS, MIN_BPS, MIN_T));
        out.append(String.format("sessions %d, tagged closed shorts %d, left out (ERROR/EMPTY) %d%n",
                r.sessions(), r.tagged(), r.excluded()));
        for (Group g : List.of(r.noNews(), r.withNews(), r.catalyst(), r.news(), r.noNewsUpDays(), r.noNewsDownDays())) {
            out.append(String.format("  %-28s n %4d  net %+7.1f bps  t %+5.2f  Rs %+,9.0f%n",
                    g.name(), g.n(), g.meanBps(), g.t(), g.totalRs()));
        }
        out.append("\nVERDICT: ").append(r.verdict()).append("\n");
        r.reasons().forEach(s -> out.append("  ").append(s).append("\n"));
        return out.toString();
    }
}
