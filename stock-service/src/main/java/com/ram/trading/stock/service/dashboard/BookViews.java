package com.ram.trading.stock.service.dashboard;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helpers for the paper books' JSON dashboard views (read-only). Database rows become camelCase maps with ISO
 * date/time strings, and closed trades become totals plus a cumulative P&amp;L curve, so a UI can draw cards, tables and
 * an equity line without parsing the text reports.
 */
public final class BookViews {

    private BookViews() {
    }

    /** snake_case column -> camelCase key; java.sql dates/times -> ISO strings; numbers rounded to 4 decimals. */
    public static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        r.forEach((k, v) -> out.put(camel(k), value(v)));
        return out;
    }

    public static List<Map<String, Object>> rows(List<Map<String, Object>> rs) {
        List<Map<String, Object>> out = new ArrayList<>(rs.size());
        rs.forEach(r -> out.add(row(r)));
        return out;
    }

    static String camel(String col) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : col.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    public static Object value(Object v) {
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.time.temporal.TemporalAccessor) {
            return v.toString();
        }
        if (v instanceof Double || v instanceof Float) {
            double x = ((Number) v).doubleValue();
            return Double.isFinite(x) ? Math.round(x * 1e4) / 1e4 : null;
        }
        return v;
    }

    /**
     * Totals over CLOSED rows (in order): count, winners, win rate, net/gross/charges, average, best, worst, and the
     * largest fall of the cumulative net from its running peak (0 when it never fell).
     */
    public static Map<String, Object> totals(List<Map<String, Object>> closed, String netCol, String grossCol, String chargesCol) {
        int n = closed.size(), wins = 0;
        double net = 0, gross = 0, charges = 0, best = 0, worst = 0, cum = 0, peak = 0, maxDd = 0;
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = closed.get(i);
            double x = d(r, netCol);
            wins += x > 0 ? 1 : 0;
            net += x;
            gross += d(r, grossCol);
            charges += d(r, chargesCol);
            best = i == 0 ? x : Math.max(best, x);
            worst = i == 0 ? x : Math.min(worst, x);
            cum += x;
            peak = Math.max(peak, cum);
            maxDd = Math.min(maxDd, cum - peak);
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("closed", n);
        t.put("winners", wins);
        t.put("winRatePct", n == 0 ? null : round(100.0 * wins / n));
        t.put("netRs", round(net));
        t.put("grossRs", round(gross));
        t.put("chargesRs", round(charges));
        t.put("avgNetRs", n == 0 ? null : round(net / n));
        t.put("bestRs", n == 0 ? null : round(best));
        t.put("worstRs", n == 0 ? null : round(worst));
        t.put("maxDrawdownRs", round(maxDd));
        return t;
    }

    /** One point per date: that date's net and the running total (rows must be in date order). */
    public static List<Map<String, Object>> curve(List<Map<String, Object>> closed, String dateCol, String netCol) {
        Map<String, Double> byDay = new LinkedHashMap<>();
        for (Map<String, Object> r : closed) {
            byDay.merge(String.valueOf(value(r.get(dateCol))), d(r, netCol), Double::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        double cum = 0;
        for (Map.Entry<String, Double> e : byDay.entrySet()) {
            cum += e.getValue();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", e.getKey());
            p.put("netRs", round(e.getValue()));
            p.put("cumulativeRs", round(cum));
            out.add(p);
        }
        return out;
    }

    public static double d(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    public static double round(double x) {
        return Math.round(x * 100) / 100.0;
    }
}
