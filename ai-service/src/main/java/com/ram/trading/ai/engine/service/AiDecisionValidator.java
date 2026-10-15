package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.decision.Decision;
import com.ram.trading.ai.engine.dto.execution.ExecutionPlan;
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

        if (recommendation == AiRecommendation.HOLD && tradeAllowed) {
            log.warn(
                    "AI DECISION VALIDATION | Contradictory HOLD + tradeAllowed=true | SAFE_HOLD");
            return safeHold("AI returned HOLD while allowing a trade.");
        }

        /*
         * A BUY or SELL may be returned as a recommendation while trading
         * is disallowed. The downstream tradeAllowed check remains authoritative.
         *
         * If execution is allowed, validate the AI-provided price plan.
         */
        if (tradeAllowed
                && (recommendation == AiRecommendation.BUY
                || recommendation == AiRecommendation.SELL)) {

            ExecutionPlan plan = response.getExecutionPlan();

            if (plan == null) {
                log.warn(
                        "AI DECISION VALIDATION | Missing execution plan | Recommendation={} | SAFE_HOLD",
                        recommendation);
                return safeHold("Allowed BUY/SELL decision has no execution plan.");
            }

            Double entry = plan.getEntry();
            Double stopLoss = plan.getStopLoss();
            Double target = plan.getTarget();

            if (!isPositiveFinite(entry)
                    || !isPositiveFinite(stopLoss)
                    || !isPositiveFinite(target)) {

                log.warn(
                        "AI DECISION VALIDATION | Invalid execution prices | Recommendation={} | SAFE_HOLD",
                        recommendation);
                return safeHold(
                        "Execution plan entry, stop-loss and target must be finite positive prices.");
            }

            boolean validPriceOrder;

            if (recommendation == AiRecommendation.BUY) {
                validPriceOrder = stopLoss < entry && entry < target;
            } else {
                validPriceOrder = target < entry && entry < stopLoss;
            }

            if (!validPriceOrder) {
                log.warn(
                        "AI DECISION VALIDATION | Invalid price order | Recommendation={} | Entry={} | StopLoss={} | Target={} | SAFE_HOLD",
                        recommendation,
                        entry,
                        stopLoss,
                        target);
                return safeHold(
                        "Execution plan prices do not match the BUY/SELL direction.");
            }
        }

        /*
         * EXIT validation remains with the caller, which must verify
         * the relevant open-position context.
         */
        log.debug(
                "AI DECISION VALIDATION | Valid | Recommendation={} | tradeAllowed={} | confidence={}",
                recommendation,
                tradeAllowed,
                confidence);

        return response;
    }

    private boolean isPositiveFinite(Double value) {
        return value != null
                && Double.isFinite(value)
                && value > 0.0;
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