package com.ram.trading.signal.engine.service.performance;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.rules.MarketContext;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupQualityScorerTest {

    private final SetupQualityScorer scorer = new SetupQualityScorer();

    private static SetupEdgeGate.EdgeDecision edge(StrategyPerformanceService.Verdict verdict) {
        return new SetupEdgeGate.EdgeDecision("ORB", "OPENING", true, 30, 50.0, 0.2, verdict, "");
    }

    @Test
    void alignedHighVolumeSetupWithRoomScoresHigh() {
        SignalGenerationRequest request = SignalGenerationRequest.builder()
                .setupStrategy("ORB")
                .setupDirection(SignalType.SELL)
                .setupVolumeRatio(3.2)
                .currentPrice(95.0)
                .ema20(100.0)
                .ema50(105.0)
                .rsi(45.0)
                .marketContext(MarketContext.builder().marketTrend(Trend.BEARISH).niftyChange(-0.8).build())
                .build();

        assertEquals(100, scorer.score(request, edge(StrategyPerformanceService.Verdict.POSITIVE_EDGE)).score());
    }

    @Test
    void counterTrendStretchedThinSetupScoresLow() {
        // NBCC-like short on 2026-10-26 but into a bullish tape: RSI 21 (oversold), volume only 1.25x.
        SignalGenerationRequest request = SignalGenerationRequest.builder()
                .setupStrategy("ORB")
                .setupDirection(SignalType.SELL)
                .setupVolumeRatio(1.25)
                .currentPrice(67.79)
                .ema20(75.74)
                .ema50(81.5)
                .rsi(21.6)
                .marketContext(MarketContext.builder().marketTrend(Trend.BULLISH).niftyChange(0.6).build())
                .build();

        int score = scorer.score(request, edge(StrategyPerformanceService.Verdict.NEGATIVE_EDGE)).score();

        // Only the daily trend agrees (20 of 100).
        assertEquals(21, score);
    }

    @Test
    void missingContextIsNeutralNotZero() {
        SignalGenerationRequest request = SignalGenerationRequest.builder()
                .setupStrategy("MIDDAY")
                .setupDirection(SignalType.BUY)
                .currentPrice(100.0)
                .build();

        int score = scorer.score(request, null).score();

        assertTrue(score > 0 && score < 40, "neutral defaults only: " + score);
    }
}
