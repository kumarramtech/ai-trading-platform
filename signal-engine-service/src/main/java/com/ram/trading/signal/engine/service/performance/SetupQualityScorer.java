package com.ram.trading.signal.engine.service.performance;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.rules.MarketContext;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import org.springframework.stereotype.Component;

/**
 * Scores one entry setup 0-100 from evidence that differs between setups, so
 * trades can be ranked. The daily-indicator rule confidence only takes three
 * values (36 / 64 / 71) and the AI keeps its confidence aligned with it, so
 * neither separates a strong setup from a weak one.
 *
 * <pre>
 *  Volume confirmation     25  confirmation volume vs opening-range average (1.2x -> 0, 3x+ -> 25)
 *  Market regime           20  index regime with the trade 20, sideways 10, unknown 5, against 0
 *  Index momentum          10  NIFTY % change in the trade direction (0.5%+ -> 10)
 *  Daily trend             20  price beyond EMA20 and EMA50 in the trade direction, 10 each
 *  Room to run (RSI)       10  not already stretched: BUY RSI < 65, SELL RSI > 35
 *  Track record            15  this strategy at this time of day: positive 15, sampling 7, negative 0
 * </pre>
 */
@Component
public class SetupQualityScorer {

    public QualityScore score(SignalGenerationRequest request, SetupEdgeGate.EdgeDecision edge) {

        SignalType direction = request.getSetupDirection();
        boolean longTrade = direction == SignalType.BUY;
        MarketContext context = request.getMarketContext();

        double volume = volumeScore(request.getSetupVolumeRatio());
        double regime = regimeScore(context == null ? null : context.getMarketTrend(), longTrade);
        double momentum = momentumScore(context == null ? null : context.getNiftyChange(), longTrade);
        double trend = trendScore(request.getCurrentPrice(), request.getEma20(), request.getEma50(), longTrade);
        double room = rsiRoomScore(request.getRsi(), longTrade);
        double record = trackRecordScore(edge);

        int total = (int) Math.round(volume + regime + momentum + trend + room + record);

        String breakdown = String.format(
                "volume=%.0f/25 regime=%.0f/20 momentum=%.0f/10 trend=%.0f/20 rsiRoom=%.0f/10 record=%.0f/15",
                volume, regime, momentum, trend, room, record);

        return new QualityScore(Math.max(0, Math.min(100, total)), breakdown);
    }

    private static double volumeScore(Double ratio) {
        if (ratio == null || !Double.isFinite(ratio)) {
            return 8;   // MIDDAY and other setups without a confirmation candle: neutral
        }
        return clamp((ratio - 1.2) / (3.0 - 1.2), 0, 1) * 25;
    }

    private static double regimeScore(Trend trend, boolean longTrade) {
        if (trend == null) {
            return 5;
        }
        if (trend == Trend.SIDEWAYS) {
            return 10;
        }
        boolean aligned = longTrade ? trend == Trend.BULLISH : trend == Trend.BEARISH;
        return aligned ? 20 : 0;
    }

    private static double momentumScore(Double niftyChangePct, boolean longTrade) {
        if (niftyChangePct == null || !Double.isFinite(niftyChangePct)) {
            return 3;
        }
        double withTrade = longTrade ? niftyChangePct : -niftyChangePct;
        return clamp(withTrade / 0.5, 0, 1) * 10;
    }

    private static double trendScore(Double price, Double ema20, Double ema50, boolean longTrade) {
        if (price == null) {
            return 0;
        }
        double score = 0;
        if (ema20 != null && (longTrade ? price > ema20 : price < ema20)) {
            score += 10;
        }
        if (ema50 != null && (longTrade ? price > ema50 : price < ema50)) {
            score += 10;
        }
        return score;
    }

    private static double rsiRoomScore(Double rsi, boolean longTrade) {
        if (rsi == null || !Double.isFinite(rsi)) {
            return 5;
        }
        // Distance into overbought (BUY) / oversold (SELL) territory: 65->75 or 35->25 fades 10 -> 0.
        double stretch = longTrade ? rsi - 65 : 35 - rsi;
        return clamp(1 - stretch / 10.0, 0, 1) * 10;
    }

    private static double trackRecordScore(SetupEdgeGate.EdgeDecision edge) {
        if (edge == null || edge.verdict() == null) {
            return 7;
        }
        return switch (edge.verdict()) {
            case POSITIVE_EDGE -> 15;
            case NEGATIVE_EDGE -> 0;
            case INSUFFICIENT_DATA -> 7;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record QualityScore(int score, String breakdown) {
    }
}
