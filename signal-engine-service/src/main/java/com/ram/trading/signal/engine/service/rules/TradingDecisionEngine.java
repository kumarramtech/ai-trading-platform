package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.ConfidenceLevel;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingDecisionEngine {

    private final RuleEngine ruleEngine;

    private final RuleConfidenceCalculator confidenceCalculator;


    public TradingDecision generateDecision(
            SignalGenerationRequest request) {

        log.info("======================================================");
        log.info(
                "V2.2 TECHNICAL DECISION STARTED | Symbol={}",
                request.getSymbol());
        log.info("======================================================");

        /*
         * ============================================================
         * STEP 1 : EXECUTE ALL TECHNICAL RULES
         * ============================================================
         */

        List<RuleResult> results =
                ruleEngine.executeRules(request);

        if (results == null || results.isEmpty()) {

            log.warn(
                    "V2.2 TECHNICAL DECISION | No rule results | Symbol={}",
                    request.getSymbol());

            return TradingDecision.builder()
                    .signal(SignalType.HOLD)
                    .confidence(0)
                    .confidenceLevel(
                            confidenceCalculator.determineLevel(0))
                    .reasons(
                            List.of(
                                    "No technical rule results"))
                    .ruleResults(
                            List.of())
                    .build();
        }


        /*
         * ============================================================
         * STEP 2 : DETERMINE DIRECTION
         *
         * IMPORTANT:
         * determineSignal() is the SINGLE source of truth for
         * BUY / SELL / HOLD.
         * ============================================================
         */

        SignalType signal =
                determineSignal(results);


        /*
         * ============================================================
         * STEP 3 : CALCULATE CONFIDENCE
         *
         * Existing RuleConfidenceCalculator remains unchanged.
         * ============================================================
         */

        int confidence =
                confidenceCalculator.calculateScore(results);

        ConfidenceLevel level =
                confidenceCalculator.determineLevel(confidence);


        /*
         * ============================================================
         * STEP 4 : CALCULATE DIAGNOSTIC SCORES
         *
         * These values are for observability only.
         * The actual direction comes from determineSignal().
         * ============================================================
         */

        int buyScore =
                results.stream()
                        .filter(result ->
                                result != null
                                        && result.getSignal()
                                        == SignalType.BUY)
                        .mapToInt(RuleResult::getScore)
                        .sum();

        int sellScore =
                results.stream()
                        .filter(result ->
                                result != null
                                        && result.getSignal()
                                        == SignalType.SELL)
                        .mapToInt(RuleResult::getScore)
                        .sum();

        int totalPossibleScore =
                results.stream()
                        .filter(Objects::nonNull)
                        .mapToInt(RuleResult::getMaxScore)
                        .sum();

        int netScore =
                buyScore - sellScore;

        double dominanceRatio =
                totalPossibleScore > 0
                        ? Math.abs(netScore)
                          / (double) totalPossibleScore
                        : 0.0;


        /*
         * ============================================================
         * STEP 5 : MAIN V2.2 DIAGNOSTIC
         * ============================================================
         */

        log.info(
                "V2.2 TECHNICAL EVIDENCE | " +
                        "Symbol={} | " +
                        "BUY_SCORE={} | " +
                        "SELL_SCORE={} | " +
                        "NET_SCORE={} | " +
                        "TOTAL_POSSIBLE={} | " +
                        "DOMINANCE={} | " +
                        "CONFIDENCE={} | " +
                        "LEVEL={} | " +
                        "FINAL_SIGNAL={}",
                request.getSymbol(),
                buyScore,
                sellScore,
                netScore,
                totalPossibleScore,
                String.format(
                        "%.2f%%",
                        dominanceRatio * 100.0),
                confidence,
                level,
                signal);


        /*
         * ============================================================
         * STEP 6 : INDIVIDUAL RULE BREAKDOWN
         * ============================================================
         */

        results.forEach(result -> {

            if (result == null) {
                return;
            }

            log.info(
                    "V2.2 RULE BREAKDOWN | " +
                            "Symbol={} | " +
                            "Rule={} | " +
                            "Signal={} | " +
                            "Score={} | " +
                            "MaxScore={} | " +
                            "Reason={}",
                    request.getSymbol(),
                    result.getRuleName(),
                    result.getSignal(),
                    result.getScore(),
                    result.getMaxScore(),
                    result.getReason());
        });


        /*
         * ============================================================
         * STEP 7 : BUILD FINAL TECHNICAL DECISION
         * ============================================================
         */

        TradingDecision decision =
                TradingDecision.builder()
                        .signal(signal)
                        .confidence(confidence)
                        .confidenceLevel(level)
                        .reasons(
                                collectReasons(results))
                        .ruleResults(results)
                        .build();


        /*
         * ============================================================
         * STEP 8 : FINAL LOG
         * ============================================================
         */

        log.info(
                "V2.2 TECHNICAL DECISION COMPLETED | " +
                        "Symbol={} | " +
                        "Signal={} | " +
                        "Confidence={} | " +
                        "BUY_SCORE={} | " +
                        "SELL_SCORE={} | " +
                        "DOMINANCE={}",
                request.getSymbol(),
                signal,
                confidence,
                buyScore,
                sellScore,
                String.format(
                        "%.2f%%",
                        dominanceRatio * 100.0));

        log.info(
                "======================================================");

        return decision;
    }

    private SignalType determineSignal(
            List<RuleResult> results) {

        /*
         * ============================================================
         * VALIDATION
         * ============================================================
         */

        if (results == null || results.isEmpty()) {

            log.warn(
                    "V2.2 SIGNAL DECISION | No rule results");

            return SignalType.HOLD;
        }


        /*
         * ============================================================
         * BUY EVIDENCE
         * ============================================================
         */

        int buyScore =
                results.stream()
                        .filter(result ->
                                result != null
                                        && result.getSignal()
                                        == SignalType.BUY)
                        .mapToInt(RuleResult::getScore)
                        .sum();


        /*
         * ============================================================
         * SELL EVIDENCE
         * ============================================================
         */

        int sellScore =
                results.stream()
                        .filter(result ->
                                result != null
                                        && result.getSignal()
                                        == SignalType.SELL)
                        .mapToInt(RuleResult::getScore)
                        .sum();


        /*
         * ============================================================
         * TOTAL AVAILABLE TECHNICAL EVIDENCE
         * ============================================================
         */

        int totalPossibleScore =
                results.stream()
                        .filter(Objects::nonNull)
                        .mapToInt(RuleResult::getMaxScore)
                        .sum();


        /*
         * ============================================================
         * NO DIRECTIONAL EVIDENCE
         * ============================================================
         */

        if (buyScore == 0
                && sellScore == 0) {

            log.info(
                    "V2.2 SIGNAL DECISION | " +
                            "BUY_SCORE=0 | SELL_SCORE=0 | " +
                            "Decision=HOLD");

            return SignalType.HOLD;
        }


        /*
         * ============================================================
         * INVALID SCORE CONFIGURATION
         * ============================================================
         */

        if (totalPossibleScore <= 0) {

            log.warn(
                    "V2.2 SIGNAL DECISION | " +
                            "Invalid total possible score={} | " +
                            "Decision=HOLD",
                    totalPossibleScore);

            return SignalType.HOLD;
        }


        /*
         * ============================================================
         * NET TECHNICAL EVIDENCE
         * ============================================================
         */

        int netScore =
                buyScore - sellScore;

        double dominanceRatio =
                Math.abs(netScore)
                        / (double) totalPossibleScore;


        /*
         * ============================================================
         * CONFLICT FILTER
         *
         * If BUY and SELL evidence are too close, do not force a
         * direction.
         *
         * 20% is our INITIAL V2.2 diagnostic threshold.
         * We will validate this with paper-trading data.
         * ============================================================
         */

        final double MINIMUM_DOMINANCE_RATIO = 0.20;

        if (dominanceRatio < MINIMUM_DOMINANCE_RATIO) {

            log.info(
                    "V2.2 SIGNAL DECISION | " +
                            "BUY_SCORE={} | " +
                            "SELL_SCORE={} | " +
                            "NET_SCORE={} | " +
                            "DOMINANCE={} | " +
                            "Decision=HOLD | Reason=Conflicting technical evidence",
                    buyScore,
                    sellScore,
                    netScore,
                    String.format(
                            "%.2f%%",
                            dominanceRatio * 100.0));

            return SignalType.HOLD;
        }


        /*
         * ============================================================
         * FINAL DIRECTION
         * ============================================================
         */

        if (buyScore > sellScore) {

            log.info(
                    "V2.2 SIGNAL DECISION | " +
                            "BUY_SCORE={} | " +
                            "SELL_SCORE={} | " +
                            "NET_SCORE={} | " +
                            "DOMINANCE={} | " +
                            "Decision=BUY",
                    buyScore,
                    sellScore,
                    netScore,
                    String.format(
                            "%.2f%%",
                            dominanceRatio * 100.0));

            return SignalType.BUY;
        }


        if (sellScore > buyScore) {

            log.info(
                    "V2.2 SIGNAL DECISION | " +
                            "BUY_SCORE={} | " +
                            "SELL_SCORE={} | " +
                            "NET_SCORE={} | " +
                            "DOMINANCE={} | " +
                            "Decision=SELL",
                    buyScore,
                    sellScore,
                    netScore,
                    String.format(
                            "%.2f%%",
                            dominanceRatio * 100.0));

            return SignalType.SELL;
        }


        /*
         * ============================================================
         * EXACT TIE
         * ============================================================
         */

        log.info(
                "V2.2 SIGNAL DECISION | " +
                        "BUY_SCORE={} | SELL_SCORE={} | " +
                        "Decision=HOLD | Reason=Equal technical evidence",
                buyScore,
                sellScore);

        return SignalType.HOLD;
    }

    private List<String> collectReasons(
            List<RuleResult> results) {

        return results.stream()
                .map(RuleResult::getReason)
                .toList();
    }

}