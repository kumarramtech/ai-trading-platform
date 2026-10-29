package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InPlayOrbTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    /** First 5 minutes rise 100 -> 101 on 200k shares, then the price climbs to 105 by the square-off. */
    private static List<MinuteCandle> risingDay(String symbol, long openingVolume) {
        List<MinuteCandle> list = new ArrayList<>();
        for (int i = 0; i < 345; i++) {
            LocalTime t = LocalTime.of(9, 15).plusMinutes(i);
            double p = i < 5 ? 100 + i * 0.2 : 101 + (i - 5) * 0.012;
            list.add(MinuteCandle.builder().symbol(symbol).minute(DAY.atTime(t))
                    .open(i == 0 ? 100.0 : p - 0.05).high(p + 0.05).low(p - 0.1).close(p)
                    .volume(i < 5 ? openingVolume / 5 : 1000L).build());
        }
        return list;
    }

    @Test
    void buysTheBreakOfTheFirstFiveMinuteHighAndHoldsToTheSquareOff() throws Exception {
        Map<String, List<MinuteCandle>> series = new HashMap<>();
        series.put("HOT", risingDay("HOT", 200_000));     // 20% of a 1M-share day in 5 minutes
        DailyBars daily = dailyBars("HOT");

        List<StrategyReplayService.Result> results = InPlayOrb.replayDay(DAY, series, daily, 50, 5, 250, 500_000,
                0.03, LocalTime.of(15, 0), new IntradayChargesCalculator());

        StrategyReplayService.Result r = results.stream()
                .filter(x -> x.strategy().equals("INPLAY_ORB5 top20 stop 10% ATR")).findFirst().orElseThrow();
        assertEquals("BUY", r.direction());
        assertEquals(LocalTime.of(9, 20), r.time());
        assertEquals("SQUARE_OFF", r.exit());
        assertEquals(0.20, r.orRvol(), 1e-9);
        // ATR 2 -> stop 0.20 below the entry; the move to ~105 is many R.
        assertEquals(r.entry() - 0.2, r.stop(), 1e-9);
        assertTrue(r.netR() > 5, "rode the day: " + r);
        assertEquals(InPlayOrb.GATE, r.gate());
        assertEquals(InPlayOrb.VARIANTS.size(), results.size());
    }

    /** 20 sessions of 1M shares at Rs 100 with a Rs 2 daily range: ATR 2, turnover Rs 10 crore. */
    private static DailyBars dailyBars(String symbol) throws Exception {
        List<DailyBars.Bar> bars = new ArrayList<>();
        for (int i = 20; i >= 1; i--) {
            bars.add(new DailyBars.Bar(DAY.minusDays(i), 101, 99, 100, 1_000_000));
        }
        Constructor<DailyBars> c = DailyBars.class.getDeclaredConstructor(Map.class);
        c.setAccessible(true);
        return c.newInstance(Map.of(symbol, bars));
    }
}
