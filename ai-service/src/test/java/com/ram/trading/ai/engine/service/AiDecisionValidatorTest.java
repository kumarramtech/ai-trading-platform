package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.decision.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDecisionValidatorTest {

    private final AiDecisionValidator validator = new AiDecisionValidator();

    private AiDecisionResponse response(AiRecommendation rec, boolean allowed, int confidence) {
        return AiDecisionResponse.builder()
                .decision(Decision.builder()
                        .recommendation(rec)
                        .tradeAllowed(allowed)
                        .confidence(confidence)
                        .build())
                .build();
    }

    @Test
    void allowedSellWithoutAnyExecutionPlanIsKept() {
        AiDecisionResponse out = validator.validate(response(AiRecommendation.SELL, true, 70));
        assertEquals(AiRecommendation.SELL, out.getDecision().getRecommendation());
        assertTrue(out.getDecision().getTradeAllowed());
    }

    @Test
    void allowedBuyWithoutAnyExecutionPlanIsKept() {
        AiDecisionResponse out = validator.validate(response(AiRecommendation.BUY, true, 70));
        assertEquals(AiRecommendation.BUY, out.getDecision().getRecommendation());
    }

    @Test
    void holdWithTradeAllowedIsStillSafeHeld() {
        AiDecisionResponse out = validator.validate(response(AiRecommendation.HOLD, true, 70));
        assertEquals(AiRecommendation.HOLD, out.getDecision().getRecommendation());
        assertFalse(out.getDecision().getTradeAllowed());
    }

    @Test
    void outOfRangeConfidenceIsStillSafeHeld() {
        AiDecisionResponse out = validator.validate(response(AiRecommendation.BUY, true, 150));
        assertFalse(out.getDecision().getTradeAllowed());
    }
}
