package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.ConfidenceLevel;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class RuleConfidenceCalculator {

    public int calculateScore(List<RuleResult> results) {

        int totalScore = results.stream()
                .mapToInt(RuleResult::getScore)
                .sum();

        log.info("Calculated Confidence Score : {}", totalScore);

        return totalScore;
    }

    public ConfidenceLevel determineLevel(int score) {

        if (score >= 80)
            return ConfidenceLevel.HIGH;

        if (score >= 60)
            return ConfidenceLevel.MEDIUM;

        return ConfidenceLevel.LOW;
    }

}