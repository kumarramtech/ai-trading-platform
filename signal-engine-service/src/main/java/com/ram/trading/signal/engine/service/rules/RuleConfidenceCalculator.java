package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.ConfidenceLevel;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class RuleConfidenceCalculator {

    public int calculateScore(List<RuleResult> results) {

        if (results == null || results.isEmpty()) {
            log.info("Calculated Confidence Score : 0");
            return 0;
        }

        int buyScore = results.stream()
                .filter(r -> r != null && r.getSignal() == SignalType.BUY)
                .mapToInt(RuleResult::getScore)
                .sum();

        int sellScore = results.stream()
                .filter(r -> r != null && r.getSignal() == SignalType.SELL)
                .mapToInt(RuleResult::getScore)
                .sum();

        /*
         * Denominator is now the TOTAL possible score across every rule
         * that participated in this evaluation — not just the rules
         * that happened to fire directionally.
         *
         * This means a single BUY rule, with the other rules neutral,
         * can no longer report 100% confidence. It can only ever reach
         * (its own maxScore / sum of all rules' maxScore) — e.g. one
         * rule out of three firing caps out around 33%, not 100%.
         */
        int totalPossibleScore = results.stream()
                .filter(r -> r != null)
                .mapToInt(RuleResult::getMaxScore)
                .sum();

        if (totalPossibleScore == 0) {
            log.info("Calculated Confidence Score : 0 | No scorable rules");
            return 0;
        }

        int dominantScore = Math.max(buyScore, sellScore);

        int confidence = (int) Math.round(
                (dominantScore * 100.0) / totalPossibleScore);

        confidence = Math.max(0, Math.min(100, confidence));

        log.info(
                "Calculated Confidence Score : {} | BUY Score : {} | SELL Score : {} | Total Possible : {}",
                confidence, buyScore, sellScore, totalPossibleScore);

        return confidence;
    }

    public ConfidenceLevel determineLevel(int score) {
        if (score >= 80) return ConfidenceLevel.HIGH;
        if (score >= 60) return ConfidenceLevel.MEDIUM;
        return ConfidenceLevel.LOW;
    }
}