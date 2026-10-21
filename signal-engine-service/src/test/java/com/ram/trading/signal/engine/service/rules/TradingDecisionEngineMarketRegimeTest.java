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
