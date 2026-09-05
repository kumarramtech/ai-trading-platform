package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.decision.Decision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates the semantic contract of an AI trading decision after JSON parsing.
 *
 * The LLM is not trusted as the final authority for basic invariants. Invalid
 * or contradictory structured decisions are converted to a safe HOLD decision.
 */
@Component
@Slf4j
public class AiDecisionValidator {

    public AiDecisionResponse validate(AiDecisionResponse response) {

        if (response == null) {
            log.warn("AI DECISION VALIDATION | Null response | SAFE_HOLD");
            return safeHold("AI response was null.");
        }

        Decision decision = response.getDecision();

        if (decision == null) {
            log.warn("AI DECISION VALIDATION | Missing decision | SAFE_HOLD");
            return safeHold("AI decision was missing.");
        }

        AiRecommendation recommendation = decision.getRecommendation();
        Boolean tradeAllowed = decision.getTradeAllowed();
        Integer confidence = decision.getConfidence();

        if (recommendation == null) {
            log.warn("AI DECISION VALIDATION | Missing recommendation | SAFE_HOLD");
            return safeHold("AI recommendation was missing.");
        }

        if (tradeAllowed == null) {
            log.warn("AI DECISION VALIDATION | Missing tradeAllowed | SAFE_HOLD");
            return safeHold("AI tradeAllowed was missing.");
        }

        if (confidence == null || confidence < 0 || confidence > 100) {
            log.warn(
                    "AI DECISION VALIDATION | Invalid confidence={} | Recommendation={} | SAFE_HOLD",
                    confidence,
                    recommendation);
            return safeHold("AI confidence was outside the valid 0-100 range.");
        }

        /*
         * HOLD is never an executable trade. If the model returns
         * HOLD + tradeAllowed=true, normalize it to the safe state.
         */
        if (AiRecommendation.HOLD == recommendation && tradeAllowed) {
            log.warn(
                    "AI DECISION VALIDATION | Contradictory HOLD + tradeAllowed=true | SAFE_HOLD");
            return safeHold("AI returned HOLD while allowing a trade.");
        }

        /*
         * EXIT is only meaningful when explicitly allowed. We do not reject
         * EXIT here because the caller owns the open-position context check.
         */
        log.debug(
                "AI DECISION VALIDATION | Valid | Recommendation={} | tradeAllowed={} | confidence={}",
                recommendation,
                tradeAllowed,
                confidence);

        return response;
    }

    private AiDecisionResponse safeHold(String reason) {
        Decision decision = Decision.builder()
                .tradeAllowed(false)
                .recommendation(AiRecommendation.HOLD)
                .confidence(0)
                .decisionStrength("INVALID_AI_RESPONSE")
                .reason(reason)
                .build();

        return AiDecisionResponse.builder()
                .decision(decision)
                .aiReasoning(reason)
                .build();
    }
}
