package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FadeLookAheadStudyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private final IntradayChargesCalculator charges = new IntradayChargesCalculator();

    /** Flat at 100 until 09:45, then {@code perMinute} rupees a minute. */
    private static List<MinuteCandle> series(double perMinute) {
        List<MinuteCandle> list = new ArrayList<>();
        for (int i = 0; i < 375; i++) {
            LocalTime t = LocalTime.of(9, 15).plusMinutes(i);
            int moving = Math.max(0, i - 30);
            double p = 100 + moving * perMinute;
            double prev = 100 + Math.max(0, moving - 1) * perMinute;
            list.add(MinuteCandle.builder().symbol("S").minute(DAY.atTime(t)).open(prev)
                    .high(Math.max(p, prev) + 0.01).low(Math.min(p, prev) - 0.01).close(p).volume(1000L).build());
        }
        return list;
    }

    @Test
    void shortHeldToTheCloseWithoutAStop() {
        // -0.01 a minute: 100 -> 96.86 at the 14:59 close, a 3.1% win on the short.
        FadeLookAheadStudy.Trade t = FadeLookAheadStudy.shortTrade(DAY, "r", series(-0.01), 0, 0.03, charges);

        assertNotNull(t);
        assertEquals(314 - 6, t.grossBps(), 1.5);           // ~314 bps less 2 x 3 bps slippage
        assertTrue(t.netBps() < t.grossBps() && t.netRs() > 1000);
    }

    @Test
    void stopCapsASqueeze() {
        FadeLookAheadStudy.Trade none = FadeLookAheadStudy.shortTrade(DAY, "r", series(0.02), 0, 0.03, charges);
        FadeLookAheadStudy.Trade stop3 = FadeLookAheadStudy.shortTrade(DAY, "r", series(0.02), 3, 0.03, charges);

        assertEquals(-628 - 6, none.grossBps(), 2);          // +6.28% by 14:59
        assertEquals(-300 - 6, stop3.grossBps(), 2);         // stopped at +3%
    }

    @Test
    void reportMarksLookAheadRowsAndPassesOnlyPointInTime() {
        List<LocalDate> sessions = new ArrayList<>();
        List<FadeLookAheadStudy.Trade> trades = new ArrayList<>();
        for (int d = 0; d < 120; d++) {
            LocalDate day = DAY.plusDays(d);
            sessions.add(day);
            for (int k = 0; k < 4; k++) {
                double bps = (d + k) % 3 == 0 ? -40 : 75;   // +36.7 gross, +23.7 net
                trades.add(new FadeLookAheadStudy.Trade(day, "A1 Round 2 replica: end 501-1000, day's top 100 of them", bps, bps - 13, (bps - 13) * 4));
                trades.add(new FadeLookAheadStudy.Trade(day, "B3 pit 501-1000", bps, bps - 13, (bps - 13) * 4));
                trades.add(new FadeLookAheadStudy.Trade(day, "B1 pit 501-600 (Round 3 pool, every candidate)", -bps, -bps - 13, (-bps - 13) * 4));
            }
        }

        String report = FadeLookAheadStudy.render(trades, sessions);

        assertTrue(report.lines().anyMatch(l -> l.startsWith("A1 ") && l.endsWith("look-ahead (not tradeable)")), report);
        assertTrue(report.lines().anyMatch(l -> l.startsWith("B3 ") && l.endsWith("PASS")), report);
        assertTrue(report.lines().anyMatch(l -> l.startsWith("B1 ") && l.endsWith("-")), report);
        assertTrue(report.lines().anyMatch(l -> l.startsWith("C1 ")), "every rule is listed, traded or not");
    }
}
