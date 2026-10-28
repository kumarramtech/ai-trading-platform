package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegimeTimelineTest {

    private static List<MinuteCandle> series(double open, IntToDoubleFunction pctAtMinute) {
        List<MinuteCandle> list = new ArrayList<>();
        LocalDateTime start = LocalDate.of(2026, 10, 28).atTime(9, 15);
        for (int i = 0; i < 60; i++) {
            double close = open * (1 + pctAtMinute.applyAsDouble(i) / 100.0);
            list.add(MinuteCandle.builder().minute(start.plusMinutes(i))
                    .open(i == 0 ? open : close).high(close).low(close).close(close).volume(0L).build());
        }
        return list;
    }

    @Test
    void bothIndicesUpIsBullishAndAShortFlipIsHeldBackFiveMinutes() {
        // Up 0.3% from 09:20; NIFTY dips to 0 for two minutes at 09:40.
        RegimeTimeline timeline = RegimeTimeline.build(
                series(22000, i -> i >= 5 && !(i == 25 || i == 26) ? 0.30 : 0.0),
                series(50000, i -> i >= 5 ? 0.30 : 0.0));

        assertEquals(Trend.SIDEWAYS, timeline.at(LocalTime.of(9, 16)).trend());
        assertEquals(Trend.BULLISH, timeline.at(LocalTime.of(9, 21)).trend());
        // Candidate SIDEWAYS at 09:40-09:41 but the regime was published at 09:20: more than 5 min, so it flips...
        assertEquals(Trend.SIDEWAYS, timeline.at(LocalTime.of(9, 40)).trend());
        // ...and the flip back to BULLISH must wait for the 5-minute hold.
        assertEquals(Trend.SIDEWAYS, timeline.at(LocalTime.of(9, 43)).trend());
        assertEquals(Trend.BULLISH, timeline.at(LocalTime.of(9, 45)).trend());
        assertEquals(LocalTime.of(9, 45), timeline.at(LocalTime.of(10, 0)).since());
    }

    @Test
    void mixedIndicesAreSideways() {
        RegimeTimeline timeline = RegimeTimeline.build(
                series(22000, i -> 0.5), series(50000, i -> -0.5));

        assertEquals(Trend.SIDEWAYS, timeline.at(LocalTime.of(9, 50)).trend());
    }
}
