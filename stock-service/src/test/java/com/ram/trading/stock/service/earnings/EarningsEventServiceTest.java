package com.ram.trading.stock.service.earnings;

import com.ram.trading.stock.service.earnings.EarningsEventService.Scored;
import com.ram.trading.stock.service.earnings.EarningsEventService.Series;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EarningsEventServiceTest {

    private static final LocalDate D0 = LocalDate.of(2026, 11, 2);

    /** n sessions from D0 (weekdays only); the stock opens at its previous close and moves by step a day. */
    private static Series series(int n, double start, double step) {
        LocalDate[] d = new LocalDate[n];
        double[] o = new double[n], c = new double[n];
        LocalDate day = D0;
        double p = start;
        for (int i = 0; i < n; i++) {
            while (day.getDayOfWeek().getValue() > 5) {
                day = day.plusDays(1);
            }
            d[i] = day;
            o[i] = p;
            p += step;
            c[i] = p;
            day = day.plusDays(1);
        }
        return new Series(d, o, c);
    }

    @Test
    void anEveningAnnouncementReactsTheNextSessionAMarketOneTheSameDay() {
        Series stock = series(70, 100, 1), nifty = series(70, 1000, 0);   // NIFTY flat
        LocalDate tue = LocalDate.of(2026, 11, 3);
        assertEquals(LocalDate.of(2026, 11, 4), EarningsEventService.score(stock, nifty, tue, "EVENING").reactionDay());
        assertEquals(tue, EarningsEventService.score(stock, nifty, tue, "MARKET").reactionDay());
        assertEquals(tue, EarningsEventService.score(stock, nifty, tue, "MORNING").reactionDay());
        // Seen on a Saturday morning: the reaction is Monday's session.
        assertEquals(LocalDate.of(2026, 11, 9),
                EarningsEventService.score(stock, nifty, LocalDate.of(2026, 11, 7), "MORNING").reactionDay());
    }

    @Test
    void reactionAndDriftAreAgainstNiftyAndEnterAtTheNextOpen() {
        Series stock = series(70, 100, 1), nifty = series(70, 1000, 0);
        Scored s = EarningsEventService.score(stock, nifty, LocalDate.of(2026, 11, 3), "MARKET");
        // Reaction day index 1: close 102 vs 101 = +0.990%, NIFTY flat.
        assertEquals((102.0 / 101 - 1) * 100, s.reactionPct(), 1e-9);
        // Entry at the next open (index 2, 102); drift20 to the close 20 sessions later (index 21, 122).
        assertEquals(102.0, s.entryPrice(), 1e-9);
        assertEquals((122.0 / 102 - 1) * 100, s.drift()[0], 1e-9);
        assertEquals((162.0 / 102 - 1) * 100, s.drift()[2], 1e-9);
    }

    @Test
    void driftsStayUnknownUntilEnoughSessionsHavePassed() {
        Series stock = series(30, 100, 1), nifty = series(30, 1000, 0);
        Scored s = EarningsEventService.score(stock, nifty, LocalDate.of(2026, 11, 3), "MARKET");
        assertEquals(20, Math.round((s.drift()[0] / 100 + 1) * 102 - 102));
        assertNull(s.drift()[1], "40 sessions not passed yet");
        assertNull(s.drift()[2]);
        // Seen after the last stored session: nothing is known yet.
        assertNull(EarningsEventService.score(stock, nifty, LocalDate.of(2026, 12, 31), "EVENING").reactionDay());
    }

    @Test
    void growthNeedsAPositiveBaseAndPeriodsStepByQuarter() {
        assertEquals(10.0, EarningsEventService.growth(110.0, 100.0), 1e-9);
        assertNull(EarningsEventService.growth(110.0, -5.0), "a loss-making base gives no %");
        assertNull(EarningsEventService.growth(Double.NaN, 100.0));
        assertNull(EarningsEventService.growth(110.0, null));
        assertEquals("Jun 2026", EarningsEventService.period(EarningsEventService.period("Sep 2026").minusMonths(3)));
        assertEquals(YearMonth.of(2025, 9), EarningsEventService.period("Sep 2026").minusMonths(12));
    }
}
