package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.risk.*;
import org.springframework.stereotype.Component;

@Component
public class ConfidenceRule implements RiskRule {

    private static final int MIN_CONFIDENCE = 20;

    @Override
    public RiskViolation validate(RiskEvaluation evaluation) {

        if (evaluation.getDecision() == null) {

            return RiskViolation.builder()
                    .passed(false)
                    .rule("Confidence")
                    .reason("AI Decision missing.")
                    .build();
        }

        Integer confidence =
                evaluation.getDecision().getConfidence();

        if (confidence == null
                || confidence < MIN_CONFIDENCE) {

            return RiskViolation.builder()
                    .passed(false)
                    .rule("Confidence")
                    .reason("Confidence below "
                            + MIN_CONFIDENCE)
                    .build();
        }

        return RiskViolation.builder()
                .passed(true)
                .rule("Confidence")
                .build();
    }
}