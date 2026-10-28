package com.ram.trading.signal.engine.service.shadow;

import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.MinuteCandleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TrendPullbackShadowStrategyTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private MinuteCandleStore store;
    private ShadowTradeService shadows;
    private TrendPullbackShadowStrategy strategy;

    @BeforeEach
    void setUp() {
        store = mock(MinuteCandleStore.class);
        shadows = mock(ShadowTradeService.class);
        strategy = new TrendPullbackShadowStrategy(store, shadows);
        at(13, 10);
    }

    private void at(int hour, int minute) {
        strategy.clock = Clock.fixed(
                ZonedDateTime.of(2026, 10, 28, hour, minute, 0, 0, IST).toInstant(), IST);
    }

    private static MinuteCandle candle(int i, double open, double high, double low, double close) {
        return MinuteCandle.builder().symbol("ABC")
                .minute(LocalDateTime.of(2026, 10, 28, 12, 0).plusMinutes(i))
                .open(open).high(high).low(low).close(close).volume(1_000L).build();
    }

    /** 40 candles trending up around 101-102 (VWAP ~101.5), then a dip to VWAP that closes green. */
    private MinuteCandle givenUptrendWithPullback() {
        List<MinuteCandle> today = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            today.add(candle(i, 100.0, 100.4, 99.8, 100.2));
        }
        for (int i = 10; i < 40; i++) {
            today.add(candle(i, 102.0, 102.5, 101.8, 102.2));
        }
        double vwap = TrendPullbackShadowStrategy.vwap(today);
        MinuteCandle pullback = candle(40, vwap + 0.10, vwap + 0.45, vwap - 0.05, vwap + 0.40);
        today.add(pullback);
        when(store.getToday("ABC")).thenReturn(today);
        return pullback;
    }

    private static Tick tick(double dayOpen) {
        return Tick.builder().symbol("ABC").dayOpenPrice(dayOpen).build();
    }

    @Test
    void bullishPullbackToVwapIsRecordedAsShadowWithTwoToOneTarget() {
        MinuteCandle pullback = givenUptrendWithPullback();

        strategy.onCompletedCandle(pullback, tick(100.0), Trend.BULLISH, 45);

        ArgumentCaptor<TradingSignal> signal = ArgumentCaptor.forClass(TradingSignal.class);
        verify(shadows).recordRejectedSignal(signal.capture(), eq("SHADOW_ONLY"));
        TradingSignal s = signal.getValue();
        assertEquals("BUY", s.getSignal());
        assertEquals("TREND_PULLBACK", s.getStrategy());
        double risk = s.getEntryPrice() - s.getStopLoss();
        assertTrue(risk >= s.getEntryPrice() * 0.006 - 0.05, "stop at least 0.6%");
        assertEquals(2.0 * risk, s.getTargetPrice() - s.getEntryPrice(), 0.11);
    }

    @Test
    void youngRegimeOutsideWindowOrStockNotMovingWithIndexIsIgnored() {
        MinuteCandle pullback = givenUptrendWithPullback();

        strategy.onCompletedCandle(pullback, tick(100.0), Trend.BULLISH, 10);   // regime too young
        strategy.onCompletedCandle(pullback, tick(100.0), Trend.SIDEWAYS, 90);  // no trend
        strategy.onCompletedCandle(pullback, tick(103.0), Trend.BULLISH, 45);   // stock down on the day
        at(11, 30);
        strategy.onCompletedCandle(pullback, tick(100.0), Trend.BULLISH, 45);   // before 12:30
        at(14, 30);
        strategy.onCompletedCandle(pullback, tick(100.0), Trend.BULLISH, 45);   // entry cut

        verify(shadows, never()).recordRejectedSignal(any(), anyString());
    }

    @Test
    void candleThatDoesNotReachVwapIsNotAPullback() {
        givenUptrendWithPullback();
        MinuteCandle highCandle = candle(41, 102.0, 102.6, 101.9, 102.4);

        strategy.onCompletedCandle(highCandle, tick(100.0), Trend.BULLISH, 45);

        verify(shadows, never()).recordRejectedSignal(any(), anyString());
    }
}
