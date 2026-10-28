package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchStrategiesTest {

    private static MinuteCandle candle(int minute, double open, double high, double low, double close) {
        return MinuteCandle.builder().symbol("ABC")
                .minute(LocalDateTime.of(2026, 10, 1, 9, 15).plusMinutes(minute))
                .open(open).high(high).low(low).close(close).volume(1000L).build();
    }

    private static OpeningRange range(double open, double high, double low, double close) {
        OpeningRange r = new OpeningRange();
        r.setSymbol("ABC");
        r.setOpen(open);
        r.setHigh(high);
        r.setLow(low);
        r.setClose(close);
        r.setVolume(200_000L);
        r.setComplete(true);
        return r;
    }

    private static final DailyBars.Stats STATS = new DailyBars.Stats(100.0, 1_000_000, 5e8);

    @Test
    void gapUpThatHoldsIsAGapGoLongWithStopBelowTheRange() {
        // Gap +2% (open 102), range 101.5-103.5 closed at 103; 09:30 close 103.2.
        TradingSignal s = ResearchStrategies.gap(candle(15, 103, 103.3, 103, 103.2),
                range(102, 103.5, 101.5, 103), STATS, LocalTime.of(9, 30)).orElseThrow();

        assertEquals(ResearchStrategies.GAP_GO, s.getStrategy());
        assertEquals("BUY", s.getSignal());
        assertTrue(s.getStopLoss() < 101.5);
    }

    @Test
    void gapUpThatFallsBackThroughTheOpenIsAGapFillShortToYesterdaysClose() {
        TradingSignal s = ResearchStrategies.gap(candle(15, 101.8, 101.9, 101.3, 101.4),
                range(102, 102.6, 101.6, 101.7), STATS, LocalTime.of(9, 30)).orElseThrow();

        assertEquals(ResearchStrategies.GAP_FILL, s.getStrategy());
        assertEquals("SELL", s.getSignal());
        assertEquals(100.0, s.getTargetPrice(), 1e-9);
    }

    @Test
    void stretchedStockThatReversesIsFadedTowardVwap() {
        List<MinuteCandle> today = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            today.add(candle(i, 100, 100.2, 99.8, 100));        // VWAP ~100
        }
        today.add(candle(40, 101.6, 102.0, 101.5, 101.9));
        MinuteCandle reversal = candle(41, 101.9, 102.1, 101.4, 101.5);   // new high, closes red, 1.5% above VWAP
        today.add(reversal);

        TradingSignal s = ResearchStrategies.meanReversion(reversal, today, LocalTime.of(10, 30)).orElseThrow();

        assertEquals("SELL", s.getSignal());
        assertTrue(s.getStopLoss() > 102.1);
        assertTrue(s.getTargetPrice() < 100.5);
    }
}
