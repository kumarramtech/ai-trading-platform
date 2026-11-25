package com.ram.trading.stock.service.dashboard;

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BookViewsTest {

    private static Map<String, Object> trade(String day, double net) {
        Map<String, Object> r = new HashMap<>();
        r.put("event_date", Date.valueOf(LocalDate.parse(day)));
        r.put("net_pnl", net);
        r.put("gross_pnl", net + 50);
        r.put("charges", 50.0);
        return r;
    }

    @Test
    void rowsBecomeCamelCaseWithIsoDates() {
        Map<String, Object> r = new HashMap<>();
        r.put("event_date", Date.valueOf(LocalDate.of(2026, 11, 23)));
        r.put("entry_at", Timestamp.valueOf(LocalDateTime.of(2026, 11, 23, 9, 20)));
        r.put("excess_pct", 1.256789123);
        r.put("qty", 439);
        Map<String, Object> v = BookViews.row(r);
        assertEquals("2026-11-23", v.get("eventDate"));
        assertEquals("2026-11-23T09:20", v.get("entryAt"));
        assertEquals(1.2568, v.get("excessPct"));
        assertEquals(439, v.get("qty"));
    }

    @Test
    void totalsAndCurve() {
        // IEX +1581, IREDA -814, then -2000: trade-level peak +1581 -> low -1233 = drawdown -2814
        List<Map<String, Object>> closed = List.of(trade("2026-11-23", 1581), trade("2026-11-23", -814),
                trade("2026-12-29", -2000));
        Map<String, Object> t = BookViews.totals(closed, "net_pnl", "gross_pnl", "charges");
        assertEquals(3, t.get("closed"));
        assertEquals(1, t.get("winners"));
        assertEquals(33.33, t.get("winRatePct"));
        assertEquals(-1233.0, t.get("netRs"));
        assertEquals(150.0, t.get("chargesRs"));
        assertEquals(1581.0, t.get("bestRs"));
        assertEquals(-2000.0, t.get("worstRs"));
        assertEquals(-2814.0, t.get("maxDrawdownRs"));
        List<Map<String, Object>> c = BookViews.curve(closed, "event_date", "net_pnl");
        assertEquals(2, c.size());
        assertEquals("2026-11-23", c.get(0).get("date"));
        assertEquals(767.0, c.get(0).get("netRs"));
        assertEquals(-1233.0, c.get(1).get("cumulativeRs"));
    }

    @Test
    void emptyBookHasNoAverages() {
        Map<String, Object> t = BookViews.totals(List.of(), "net_pnl", "gross_pnl", "charges");
        assertEquals(0, t.get("closed"));
        assertNull(t.get("winRatePct"));
        assertNull(t.get("worstRs"));
        assertEquals(0.0, t.get("maxDrawdownRs"));
    }
}
