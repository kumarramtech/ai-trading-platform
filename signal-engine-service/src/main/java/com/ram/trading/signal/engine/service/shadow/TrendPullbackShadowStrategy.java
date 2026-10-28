package com.ram.trading.signal.engine.service.shadow;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.MinuteCandleStore;
import com.ram.trading.signal.engine.util.PriceTicks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Afternoon trend-pullback, SHADOW ONLY: it never opens a paper or live position.
 *
 * Fills the 12:30-14:30 gap after ORB ends, when only the 30-minute MIDDAY job
 * looks for entries. Once the index regime has held BULLISH (BEARISH) for
 * minRegimeMinutes, a stock already up (down) on the day that pulls back to its
 * intraday VWAP and closes a 1-minute candle back on the trend side of it is
 * followed as a shadow trade (rejectedBy=SHADOW_ONLY, strategy=TREND_PULLBACK).
 *
 * Stop just beyond the pullback low (high) or VWAP, at least minStopPct, skipped
 * above maxStopPct; target rewardRisk x risk. The go-live report then shows
 * whether the idea beats its charges before it is allowed to trade.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrendPullbackShadowStrategy {

    public static final String STRATEGY = "TREND_PULLBACK";
    public static final String REJECTED_BY = "SHADOW_ONLY";

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final MinuteCandleStore minuteCandleStore;
    private final ShadowTradeService shadowTradeService;

    Clock clock = Clock.system(MARKET_ZONE);

    @Value("${trading.strategy.trend-pullback.enabled:true}")
    private boolean enabled = true;

    @Value("${trading.strategy.trend-pullback.start:12:30}")
    private String start = "12:30";

    @Value("${trading.strategy.trend-pullback.end:14:30}")
    private String end = "14:30";

    /** The index regime must have held this long, so a short flip is not followed. */
    @Value("${trading.strategy.trend-pullback.min-regime-minutes:30}")
    private long minRegimeMinutes = 30;

    /** The stock must already move with the index by this much since today's open. */
    @Value("${trading.strategy.trend-pullback.min-day-move-pct:0.5}")
    private double minDayMovePct = 0.5;

    /** The pullback candle must reach within this % of VWAP. */
    @Value("${trading.strategy.trend-pullback.vwap-touch-pct:0.10}")
    private double vwapTouchPct = 0.10;

    /** Of the last trendLookback closes, at least this share on the trend side of VWAP. */
    @Value("${trading.strategy.trend-pullback.min-trend-share:0.70}")
    private double minTrendShare = 0.70;

    @Value("${trading.strategy.trend-pullback.trend-lookback:30}")
    private int trendLookback = 30;

    @Value("${trading.strategy.trend-pullback.min-price:50}")
    private double minPrice = 50;

    @Value("${trading.execution-plan.min-stop-pct:0.60}")
    private double minStopPct = 0.60;

    @Value("${trading.execution-plan.max-risk-pct:1.00}")
    private double maxStopPct = 1.00;

    @Value("${trading.execution-plan.reward-risk:2.0}")
    private double rewardRisk = 2.0;

    @Value("${trading.execution-plan.or-buffer-pct:0.05}")
    private double stopBufferPct = 0.05;

    @Value("${trading.price.tick-size:0.05}")
    private double priceTickSize = 0.05;

    /**
     * Called for every completed 1-minute candle. Never throws and never trades.
     *
     * @param regime        live index regime, null when unknown or stale
     * @param regimeMinutes how long that regime has held
     */
    public void onCompletedCandle(MinuteCandle candle, Tick tick, Trend regime, long regimeMinutes) {
        try {
            evaluate(candle, tick, regime, regimeMinutes);
        } catch (Exception ex) {
            log.warn("TREND PULLBACK SHADOW | Evaluation failed | Symbol={} | Error={}",
                    candle != null ? candle.getSymbol() : null, ex.getMessage());
        }
    }

    private void evaluate(MinuteCandle candle, Tick tick, Trend regime, long regimeMinutes) {

        if (!enabled || candle == null || tick == null
                || (regime != Trend.BULLISH && regime != Trend.BEARISH)
                || regimeMinutes < minRegimeMinutes) {
            return;
        }

        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(LocalTime.parse(start)) || !now.isBefore(LocalTime.parse(end))) {
            return;
        }

        Double close = candle.getClose();
        Double open = candle.getOpen();
        Double high = candle.getHigh();
        Double low = candle.getLow();
        Double dayOpen = tick.getDayOpenPrice();

        if (close == null || open == null || high == null || low == null
                || close < minPrice || dayOpen == null || dayOpen <= 0) {
            return;
        }

        boolean buy = regime == Trend.BULLISH;
        double dayMovePct = (close - dayOpen) / dayOpen * 100.0;
        if (buy ? dayMovePct < minDayMovePct : dayMovePct > -minDayMovePct) {
            return;
        }

        List<MinuteCandle> today = minuteCandleStore.getToday(candle.getSymbol());
        if (today.size() < trendLookback) {
            return;
        }

        Double vwap = vwap(today);
        if (vwap == null) {
            return;
        }

        double touch = vwap * vwapTouchPct / 100.0;
        boolean pullback = buy
                ? low <= vwap + touch && close > vwap && close > open
                : high >= vwap - touch && close < vwap && close < open;
        if (!pullback || !trendHeld(today, vwap, buy)) {
            return;
        }

        double buffer = close * stopBufferPct / 100.0;
        double structuralStop = buy ? Math.min(low, vwap) - buffer : Math.max(high, vwap) + buffer;
        double risk = Math.max(Math.abs(close - structuralStop), close * minStopPct / 100.0);
        if (risk > close * maxStopPct / 100.0) {
            log.debug("TREND PULLBACK SHADOW | Stop too wide | Symbol={} | RiskPct={}",
                    candle.getSymbol(), risk / close * 100.0);
            return;
        }

        String side = buy ? SignalType.BUY.name() : SignalType.SELL.name();
        double stop = PriceTicks.alignStop(buy ? close - risk : close + risk, side, priceTickSize);
        double target = PriceTicks.alignTarget(buy ? close + rewardRisk * risk : close - rewardRisk * risk,
                side, priceTickSize);

        log.info("TREND PULLBACK SHADOW | Symbol={} | Direction={} | Regime={} for {} min | Entry={} | VWAP={} | Stop={} | Target={} | DayMovePct={}",
                candle.getSymbol(), side, regime, regimeMinutes, close, round2(vwap), stop, target, round2(dayMovePct));

        shadowTradeService.recordRejectedSignal(
                TradingSignal.builder()
                        .symbol(candle.getSymbol())
                        .signal(side)
                        .strategy(STRATEGY)
                        .marketRegime(regime.name())
                        .entryPrice(close)
                        .stopLoss(stop)
                        .targetPrice(target)
                        .build(),
                REJECTED_BY);
    }

    /** Volume-weighted typical price of today's candles; null without volume. */
    static Double vwap(List<MinuteCandle> candles) {
        double value = 0.0;
        double volume = 0.0;
        for (MinuteCandle c : candles) {
            if (c.getHigh() == null || c.getLow() == null || c.getClose() == null
                    || c.getVolume() == null || c.getVolume() <= 0) {
                continue;
            }
            double typical = (c.getHigh() + c.getLow() + c.getClose()) / 3.0;
            value += typical * c.getVolume();
            volume += c.getVolume();
        }
        return volume > 0 ? value / volume : null;
    }

    private boolean trendHeld(List<MinuteCandle> today, double vwap, boolean buy) {
        List<MinuteCandle> recent = today.subList(today.size() - trendLookback, today.size());
        long onSide = recent.stream()
                .filter(c -> c.getClose() != null && (buy ? c.getClose() > vwap : c.getClose() < vwap))
                .count();
        return onSide >= Math.ceil(minTrendShare * trendLookback);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
