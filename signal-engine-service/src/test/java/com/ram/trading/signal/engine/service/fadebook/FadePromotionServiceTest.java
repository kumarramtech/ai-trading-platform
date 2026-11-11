package com.ram.trading.signal.engine.service.fadebook;

import com.ram.trading.signal.engine.service.fadebook.FadePromotionService.Row;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FadePromotionServiceTest {

    private static final LocalDate D0 = LocalDate.of(2026, 11, 4);

    /** {@code sessions} days of 5 shorts; bps and hedged bps alternate around the given means. */
    private static List<Row> book(int sessions, double bps, double hedged, double netPerShort) {
        List<Row> rows = new ArrayList<>();
        for (int d = 0; d < sessions; d++) {
            for (int k = 0; k < 5; k++) {
                double wobble = (k % 2 == 0 ? 20 : -20);
                rows.add(new Row(D0.plusDays(d), "S" + k, bps + wobble, hedged + wobble, netPerShort));
            }
        }
        return rows;
    }

    @Test
    void theTestCountsOnlySessionsAfterTheRulesWereFixed() {
        assertEquals(LocalDate.of(2026, 11, 12), FadePromotionService.TEST_SINCE, "decided once; never moved");
        var r = FadePromotionService.evaluate(List.of());
        assertEquals("COLLECTING 0/60 sessions", r.verdict());
        assertEquals(0, r.trades());
    }

    @Test
    void noVerdictBeforeSixtySessions() {
        var r = FadePromotionService.evaluate(book(59, 40, 20, 100));
        assertEquals("COLLECTING 59/60 sessions", r.verdict());
        assertEquals(5, r.checks().size(), "the running state is still shown");
    }

    @Test
    void passesOnlyWhenEveryCheckHolds() {
        var r = FadePromotionService.evaluate(book(60, 40, 20, 100));
        assertEquals("PASSED", r.verdict(), String.join("\n", r.checks()));
        assertTrue(r.next().contains("live pilot"));
    }

    @Test
    void failsWhenTheProfitIsOnlyMarketDirection() {
        // Good net, but hedged against NIFTY the shorts lose: the market fell, the picks did not.
        var r = FadePromotionService.evaluate(book(60, 40, -5, 100));
        assertEquals("FAILED", r.verdict());
        assertTrue(r.checks().stream().anyMatch(c -> c.startsWith("FAIL NIFTY-hedged")), String.join("\n", r.checks()));
        assertTrue(r.next().contains("Stop FADE"));
    }

    @Test
    void failsOnALosingHalfOrABadDay() {
        List<Row> rows = book(60, 40, 20, 100);
        // First 30 sessions lose: the halves check fails even though the average may hold.
        rows.replaceAll(x -> x.day().isBefore(D0.plusDays(30))
                ? new Row(x.day(), x.symbol(), -10, x.hedgedBps(), x.net()) : x);
        rows.add(new Row(D0.plusDays(10), "BIG", 0, 0.0, -6000));
        var r = FadePromotionService.evaluate(rows);
        assertEquals("FAILED", r.verdict());
        assertTrue(r.checks().stream().anyMatch(c -> c.startsWith("FAIL halves")), String.join("\n", r.checks()));
        assertTrue(r.checks().stream().anyMatch(c -> c.startsWith("FAIL worst day")), String.join("\n", r.checks()));
    }
}
