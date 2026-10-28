package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.service.shadow.TrendPullbackShadowStrategy;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Replay-only research strategies, not wired to live trading. They test the
 * two ideas a breakout system lacks:
 *
 *  MEAN_REVERSION  10:00-14:30, a stock stretched >= stretchPct from its
 *                  intraday VWAP prints a reversal candle (new 15-minute
 *                  extreme, then closes back against it). Fade toward VWAP:
 *                  stop beyond the extreme, target VWAP (>= 1R away).
 *
 *  GAP_GO          09:30, an in-play stock (first 15 min >= 10% of a normal
 *                  day's volume) gapped >= 1.5% and holds it (above the OR
 *                  midpoint, OR closed beyond the open): trade with the gap,
 *                  stop beyond the far side of the opening range, 2R target.
 *
 *  GAP_FILL        Same gap, but price has fallen back through the open:
 *                  trade toward yesterday's close, stop beyond the OR extreme,
 *                  target the close (gap filled).
 */
final class ResearchStrategies {

    static final String MEAN_REVERSION = "MEAN_REVERSION";
    static final String GAP_GO = "GAP_GO";
    static final String GAP_FILL = "GAP_FILL";
    static final String GAP_GO_WIDE = "GAP_GO_WIDE";
    static final String GAP_GO_LATE = "GAP_GO_LATE";

    static final double STRETCH_PCT = 1.2;
    // 1.0, not 1.5: the report splits results by gap size, so smaller gaps add sample, not bias.
    static final double MIN_GAP_PCT = 1.0;
    static final double WIDE_MIN_STOP_PCT = 1.0;
    static final double WIDE_MAX_STOP_PCT = 4.0;
    static final double MIN_OPENING_RVOL = 0.10;
    static final double MIN_STOP_PCT = 0.60;
    static final double MAX_STOP_PCT = 1.50;
    static final double MAX_GAP_STOP_PCT = 3.0;
    static final double BUFFER_PCT = 0.05;

    private static final LocalTime MR_START = LocalTime.of(10, 0);
    private static final LocalTime MR_END = LocalTime.of(14, 30);
    private static final LocalTime GAP_TIME = LocalTime.of(9, 30);
    private static final LocalTime LATE_GAP_TIME = LocalTime.of(9, 45);

    private ResearchStrategies() {
    }

    static boolean isRegimeFree(String strategy) {
        return MEAN_REVERSION.equals(strategy) || (strategy != null && strategy.startsWith("GAP_"));
    }

    /** @param today the symbol's candles so far, ending with {@code candle} */
    static Optional<TradingSignal> meanReversion(MinuteCandle candle, List<MinuteCandle> today, LocalTime t) {
        if (t.isBefore(MR_START) || !t.isBefore(MR_END) || today.size() < 30) {
            return Optional.empty();
        }
        Double vwap = TrendPullbackShadowStrategy.vwap(today);
        if (vwap == null || vwap <= 0) {
            return Optional.empty();
        }
        double close = candle.getClose();
        double deviation = (close - vwap) / vwap * 100.0;
        MinuteCandle previous = today.get(today.size() - 2);
        List<MinuteCandle> recent = today.subList(today.size() - 15, today.size());

        boolean fadeUp = deviation >= STRETCH_PCT
                && candle.getHigh() >= recent.stream().mapToDouble(MinuteCandle::getHigh).max().orElse(Double.MAX_VALUE)
                && close < candle.getOpen() && close < previous.getClose();
        boolean fadeDown = deviation <= -STRETCH_PCT
                && candle.getLow() <= recent.stream().mapToDouble(MinuteCandle::getLow).min().orElse(0)
                && close > candle.getOpen() && close > previous.getClose();
        if (!fadeUp && !fadeDown) {
            return Optional.empty();
        }

        List<MinuteCandle> last3 = today.subList(today.size() - 3, today.size());
        double extreme = fadeUp
                ? last3.stream().mapToDouble(MinuteCandle::getHigh).max().orElse(close)
                : last3.stream().mapToDouble(MinuteCandle::getLow).min().orElse(close);
        double buffer = close * BUFFER_PCT / 100.0;
        double risk = Math.max(Math.abs((fadeUp ? extreme + buffer : extreme - buffer) - close),
                close * MIN_STOP_PCT / 100.0);
        if (risk > close * MAX_STOP_PCT / 100.0 || Math.abs(close - vwap) < risk) {
            return Optional.empty();   // stop too wide, or VWAP less than 1R away
        }
        return Optional.of(signal(candle.getSymbol(), MEAN_REVERSION, fadeUp ? "SELL" : "BUY",
                close, fadeUp ? close + risk : close - risk, vwap));
    }

    /** Evaluated once, on the 09:30 candle. */
    static Optional<TradingSignal> gap(MinuteCandle candle, OpeningRange range, DailyBars.Stats stats, LocalTime t) {
        if (!t.equals(GAP_TIME) || stats == null || stats.prevClose() <= 0 || stats.avgVolume20() <= 0
                || range.getVolume() == null || range.getOpen() == null) {
            return Optional.empty();
        }
        double open = range.getOpen();
        double gapPct = (open - stats.prevClose()) / stats.prevClose() * 100.0;
        if (Math.abs(gapPct) < MIN_GAP_PCT || range.getVolume() / stats.avgVolume20() < MIN_OPENING_RVOL) {
            return Optional.empty();
        }
        boolean gapUp = gapPct > 0;
        double price = candle.getClose();
        double mid = (range.getHigh() + range.getLow()) / 2.0;
        double buffer = price * BUFFER_PCT / 100.0;

        boolean holds = gapUp ? price > mid && range.getClose() > open : price < mid && range.getClose() < open;
        boolean fades = gapUp ? price < open : price > open;

        if (holds) {
            double stop = gapUp ? range.getLow() - buffer : range.getHigh() + buffer;
            double risk = Math.max(Math.abs(price - stop), price * MIN_STOP_PCT / 100.0);
            if (risk > price * MAX_GAP_STOP_PCT / 100.0) {
                return Optional.empty();
            }
            return Optional.of(signal(candle.getSymbol(), GAP_GO, gapUp ? "BUY" : "SELL", price,
                    gapUp ? price - risk : price + risk, gapUp ? price + 2 * risk : price - 2 * risk));
        }
        return fades ? gapFill(candle, range, stats, gapUp, price, buffer) : Optional.empty();
    }

    /**
     * Cost-reduction variant of GAP_GO: same trigger, stop 1.5x further (at least
     * 1%, at most 4%). A wider stop makes the fixed slippage and brokerage a smaller
     * share of R, and gives the gap room to breathe.
     */
    static Optional<TradingSignal> gapGoWide(MinuteCandle candle, OpeningRange range, DailyBars.Stats stats, LocalTime t) {
        return gap(candle, range, stats, t)
                .filter(s -> GAP_GO.equals(s.getStrategy()))
                .flatMap(s -> {
                    boolean buy = "BUY".equals(s.getSignal());
                    double price = s.getEntryPrice();
                    double risk = Math.max(Math.abs(price - s.getStopLoss()) * 1.5, price * WIDE_MIN_STOP_PCT / 100.0);
                    if (risk > price * WIDE_MAX_STOP_PCT / 100.0) {
                        return Optional.empty();
                    }
                    return Optional.of(signal(s.getSymbol(), GAP_GO_WIDE, s.getSignal(), price,
                            buy ? price - risk : price + risk, buy ? price + 2 * risk : price - 2 * risk));
                });
    }

    /**
     * Confirmation variant: at 09:45, an in-play gap stock trading beyond its opening
     * range in the gap direction (gap up above the OR high). Stop at the OR midpoint.
     */
    static Optional<TradingSignal> gapGoLate(MinuteCandle candle, OpeningRange range, DailyBars.Stats stats, LocalTime t) {
        if (!t.equals(LATE_GAP_TIME) || stats == null || stats.prevClose() <= 0 || stats.avgVolume20() <= 0
                || range.getVolume() == null || range.getOpen() == null) {
            return Optional.empty();
        }
        double gapPct = (range.getOpen() - stats.prevClose()) / stats.prevClose() * 100.0;
        if (Math.abs(gapPct) < MIN_GAP_PCT || range.getVolume() / stats.avgVolume20() < MIN_OPENING_RVOL) {
            return Optional.empty();
        }
        boolean gapUp = gapPct > 0;
        double price = candle.getClose();
        if (gapUp ? price <= range.getHigh() : price >= range.getLow()) {
            return Optional.empty();
        }
        double mid = (range.getHigh() + range.getLow()) / 2.0;
        double risk = Math.max(Math.abs(price - mid) + price * BUFFER_PCT / 100.0, price * MIN_STOP_PCT / 100.0);
        if (risk > price * MAX_GAP_STOP_PCT / 100.0) {
            return Optional.empty();
        }
        return Optional.of(signal(candle.getSymbol(), GAP_GO_LATE, gapUp ? "BUY" : "SELL", price,
                gapUp ? price - risk : price + risk, gapUp ? price + 2 * risk : price - 2 * risk));
    }

    private static Optional<TradingSignal> gapFill(MinuteCandle candle, OpeningRange range, DailyBars.Stats stats,
                                                   boolean gapUp, double price, double buffer) {
        double stop = gapUp ? range.getHigh() + buffer : range.getLow() - buffer;
        double risk = Math.max(Math.abs(stop - price), price * MIN_STOP_PCT / 100.0);
        double target = stats.prevClose();
        if (risk > price * MAX_GAP_STOP_PCT / 100.0 || Math.abs(price - target) < risk) {
            return Optional.empty();
        }
        return Optional.of(signal(candle.getSymbol(), GAP_FILL, gapUp ? "SELL" : "BUY", price,
                gapUp ? price + risk : price - risk, target));
    }

    private static TradingSignal signal(String symbol, String strategy, String side, double entry,
                                        double stop, double target) {
        return TradingSignal.builder().symbol(symbol).strategy(strategy).signal(side)
                .entryPrice(entry).stopLoss(stop).targetPrice(target).build();
    }
}
