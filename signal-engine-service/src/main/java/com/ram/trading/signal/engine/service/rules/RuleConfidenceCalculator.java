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
                .filter(result ->
                        result != null
                                && result.getSignal() == SignalType.BUY)
                .mapToInt(RuleResult::getScore)
                .sum();

        int sellScore = results.stream()
                .filter(result ->
                        result != null
                                && result.getSignal() == SignalType.SELL)
                .mapToInt(RuleResult::getScore)
                .sum();

        int totalDirectionalScore = buyScore + sellScore;

        if (totalDirectionalScore == 0) {

            log.info(
                    "Calculated Confidence Score : 0 | " +
                            "No directional rule signals");

            return 0;
        }

        /*
         * Confidence represents the percentage of directional
         * agreement between BUY and SELL rules.
         *
         * Example:
         *
         * BUY  = 75
         * SELL = 0
         *
         * Confidence = 100
         *
         * BUY  = 50
         * SELL = 25
         *
         * Confidence = 67
         *
         * BUY  = 25
         * SELL = 25
         *
         * Confidence = 50
         *
         * The final TradingDecisionEngine will still determine
         * whether the direction is BUY or SELL.
         */

        int dominantScore =
                Math.max(buyScore, sellScore);

        int confidence =
                (int) Math.round(
                        (dominantScore * 100.0)
                                / totalDirectionalScore);

        /*
         * Safety bounds.
         */

        confidence = Math.max(0, Math.min(100, confidence));

        log.info(
                "Calculated Confidence Score : {} | BUY Score : {} | SELL Score : {}",
                confidence,
                buyScore,
                sellScore);

        return confidence;
    }

    public ConfidenceLevel determineLevel(int score) {

        if (score >= 80)
            return ConfidenceLevel.HIGH;

        if (score >= 60)
            return ConfidenceLevel.MEDIUM;

        return ConfidenceLevel.LOW;
    }

}