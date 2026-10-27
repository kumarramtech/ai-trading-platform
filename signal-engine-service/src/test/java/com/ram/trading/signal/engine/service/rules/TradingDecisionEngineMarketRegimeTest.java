package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.rules.MarketContext;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradingDecisionEngineMarketRegimeTest {

    private RuleEngine ruleEngine;
    private TradingDecisionEngine decisionEngine;

    @BeforeEach
    void setUp() {
        ruleEngine = mock(RuleEngine.class);
        decisionEngine = new TradingDecisionEngine(
                ruleEngine,
                new RuleConfidenceCalculator());
    }

    @Test
    void rejectsCounterTrendOrbSellInBullishRegime() {
        assertDecision("ORB", SignalType.SELL, Trend.BULLISH, SignalType.HOLD);
    }

    @Test
    void rejectsCounterTrendOrbBuyInBearishRegime() {
        assertDecision("ORB", SignalType.BUY, Trend.BEARISH, SignalType.HOLD);
    }

    @Test
    void allowsSidewaysOrbToReachFinalAiValidation() {
        assertDecision("ORB", SignalType.BUY, Trend.SIDEWAYS, SignalType.BUY);
    }

    @Test
    void allowsSidewaysOrbSellToReachFinalAiValidation() {
        assertDecision("ORB", SignalType.SELL, Trend.SIDEWAYS, SignalType.SELL);
    }

    @Test
    void allowsCounterTrendJudasToReachFinalAiValidation() {
        assertDecision("JUDAS", SignalType.SELL, Trend.BULLISH, SignalType.SELL);
    }

    @Test
    void rejectsJudasWhenMarketRegimeIsMissing() {
        assertDecision("JUDAS", SignalType.SELL, null, SignalType.HOLD);
    }

    @Test
    void preservesNonJudasOrbPathWhenItsDtoDoesNotCarryRegime() {
        assertDecision("MIDDAY", SignalType.SELL, null, SignalType.SELL);
    }

    @Test
    void orbBuyAgainstBearishDailyRulesIsOverriddenWithLowConfidence() {
        TradingDecision decision = decide("ORB", SignalType.BUY, SignalType.SELL, Trend.BULLISH);

        assertEquals(SignalType.BUY, decision.getSignal());
        assertEquals(60, decision.getConfidence());
        org.junit.jupiter.api.Assertions.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("Daily indicators do not confirm")));
    }

    @Test
    void sidewaysRegimeKeepsTheDailyRulesAsTheFilter() {
        assertEquals(SignalType.HOLD,
                decide("ORB", SignalType.BUY, SignalType.SELL, Trend.SIDEWAYS).getSignal());
        assertEquals(SignalType.HOLD,
                decide("JUDAS", SignalType.SELL, SignalType.BUY, Trend.SIDEWAYS).getSignal());
    }

    @Test
    void bearishRegimeLetsAJudasSellOverrideBullishDailyRules() {
        assertEquals(SignalType.SELL,
                decide("JUDAS", SignalType.SELL, SignalType.BUY, Trend.BEARISH).getSignal());
    }

    @Test
    void overriddenOrbStillFacesTheRegimeGuard() {
        assertEquals(SignalType.HOLD,
                decide("ORB", SignalType.BUY, SignalType.SELL, Trend.BEARISH).getSignal());
    }

    @Test
    void middayConflictWithDailyRulesStaysHold() {
        assertEquals(SignalType.HOLD,
                decide("MIDDAY", SignalType.BUY, SignalType.SELL, null).getSignal());
    }

    @Test
    void conflictStaysHoldWhenOverrideIsOff() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                decisionEngine, "intradayOverridesDaily", false);

        assertEquals(SignalType.HOLD,
                decide("ORB", SignalType.BUY, SignalType.SELL, Trend.BULLISH).getSignal());
    }

    @Test
    void counterRegimeOrbWithOwnRelativeStrengthIsAllowedAsAnException() {
        // Stock -2.0% while NIFTY +0.3%: 2.3% relative weakness, heavy volume, decent quality.
        TradingDecision decision = decideCounterTrend(-2.0, 0.3, 2.5, 60);

        assertEquals(SignalType.SELL, decision.getSignal());
        org.junit.jupiter.api.Assertions.assertTrue(decision.isCounterTrendException());
        org.junit.jupiter.api.Assertions.assertNull(decision.getFilterRejection());
    }

    @Test
    void counterRegimeOrbWithoutHeavyVolumeIsRejectedAndShadowTagged() {
        TradingDecision decision = decideCounterTrend(-2.0, 0.3, 1.5, 60);

        assertEquals(SignalType.HOLD, decision.getSignal());
        assertEquals("REGIME_GUARD", decision.getFilterRejection());
        org.junit.jupiter.api.Assertions.assertFalse(decision.isCounterTrendException());
    }

    @Test
    void counterRegimeOrbThatOnlyFollowsTheMarketIsRejected() {
        // Stock -0.5% with NIFTY +0.3%: only 0.8% relative, below the 1.5% floor.
        assertEquals(SignalType.HOLD, decideCounterTrend(-0.5, 0.3, 3.0, 65).getSignal());
    }

    @Test
    void counterRegimeExceptionIsOffInLiveModeByDefault() {
        org.springframework.test.util.ReflectionTestUtils.setField(decisionEngine, "executionTradeMode", "live");

        assertEquals(SignalType.HOLD, decideCounterTrend(-2.0, 0.3, 2.5, 60).getSignal());
    }

    private TradingDecision decideCounterTrend(
            double stockChange, double niftyChange, double volumeRatio, int quality) {

        when(ruleEngine.executeRules(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(RuleResult.builder()
                        .signal(SignalType.SELL).score(8).maxScore(10)
                        .ruleName("test-rule").reason("daily downtrend").build()));

        return decisionEngine.generateDecision(SignalGenerationRequest.builder()
                .symbol("TATACAP")
                .setupStrategy("ORB")
                .setupDirection(SignalType.SELL)
                .stockChangePct(stockChange)
                .setupVolumeRatio(volumeRatio)
                .setupQualityScore(quality)
                .marketContext(MarketContext.builder()
                        .marketTrend(Trend.BULLISH)
                        .niftyChange(niftyChange)
                        .build())
                .build());
    }

    private TradingDecision decide(
            String strategy,
            SignalType setupDirection,
            SignalType ruleSignal,
            Trend regime) {

        when(ruleEngine.executeRules(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(RuleResult.builder()
                        .signal(ruleSignal).score(8).maxScore(10)
                        .ruleName("test-rule").reason("daily evidence").build()));

        return decisionEngine.generateDecision(SignalGenerationRequest.builder()
                .symbol("TEST")
                .setupStrategy(strategy)
                .setupDirection(setupDirection)
                .marketContext(regime == null ? null
                        : MarketContext.builder().marketTrend(regime).build())
                .build());
    }

    private void assertDecision(
            String strategy,
            SignalType setupDirection,
            Trend regime,
            SignalType expected) {

        RuleResult result = RuleResult.builder()
                .signal(setupDirection)
                .score(8)
                .maxScore(10)
                .ruleName("test-rule")
                .reason("test technical confirmation")
                .build();

        when(ruleEngine.executeRules(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(result));

        MarketContext context = regime == null
                ? null
                : MarketContext.builder().marketTrend(regime).build();

        SignalGenerationRequest request = SignalGenerationRequest.builder()
                .symbol("TEST")
                .setupStrategy(strategy)
                .setupDirection(setupDirection)
                .marketContext(context)
                .build();

        TradingDecision decision = decisionEngine.generateDecision(request);
        assertEquals(expected, decision.getSignal());
    }
}
