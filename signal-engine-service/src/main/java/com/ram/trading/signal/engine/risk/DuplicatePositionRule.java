package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.risk.*;
import org.springframework.stereotype.Component;

@Component
public class DuplicatePositionRule implements RiskRule {

    @Override
    public RiskViolation validate(RiskEvaluation evaluation) {

        if (evaluation.getContext() == null
                || evaluation.getContext().getOpenPositionContext() == null) {

            return RiskViolation.builder()
                    .passed(true)
                    .rule("DuplicatePosition")
                    .build();
        }

        var position =
                evaluation.getContext().getOpenPositionContext();

        if (position.isPositionExists()) {

            return RiskViolation.builder()
                    .passed(false)
                    .rule("DuplicatePosition")
                    .reason("Open position already exists for "
                            + position.getSymbol())
                    .build();
        }

        return RiskViolation.builder()
                .passed(true)
                .rule("DuplicatePosition")
                .build();
    }
}