package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.ConfidenceLevel;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RuleConfidenceCalculator {

    public int calculateScore(List<RuleResult> results) {

        return results.stream()
                .mapToInt(RuleResult::getScore)
                .sum();
    }

    public ConfidenceLevel determineLevel(int score) {

        if (score >= 80)
            return ConfidenceLevel.HIGH;

        if (score >= 60)
            return ConfidenceLevel.MEDIUM;

        return ConfidenceLevel.LOW;
    }

}