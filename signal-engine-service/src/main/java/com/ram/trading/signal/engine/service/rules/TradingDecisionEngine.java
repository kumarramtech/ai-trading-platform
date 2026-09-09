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

    private static final double MINIMUM_DOMINANCE_RATIO = 0.20;

    private final RuleEngine ruleEngine;
    private final RuleConfidenceCalculator confidenceCalculator;

    public TradingDecision generateDecision(SignalGenerationRequest request) {

        if (request == null) {
            log.warn("V2.2 TECHNICAL DECISION | Request is null | Decision=HOLD");
            return holdDecision("Null technical request");
        }

        List<RuleResult> results = ruleEngine.executeRules(request);

        if (results == null || results.isEmpty()) {
            log.warn("V2.2 TECHNICAL DECISION | No rule results | Symbol={} | Decision=HOLD",
                    request.getSymbol());
            return holdDecision("No technical rule results");
        }

        int buyScore = score(results, SignalType.BUY);
        int sellScore = score(results, SignalType.SELL);
        int totalPossibleScore = results.stream()
                .filter(Objects::nonNull)
                .mapToInt(RuleResult::getMaxScore)
                .sum();

        SignalType setupDirection = request.getSetupDirection();
        SignalType signal = determineSignal(results, setupDirection);

        int confidence = confidenceCalculator.calculateScore(results);
        ConfidenceLevel level = confidenceCalculator.determineLevel(confidence);

        int netScore = buyScore - sellScore;
        double dominanceRatio = totalPossibleScore > 0
                ? Math.abs(netScore) / (double) totalPossibleScore
                : 0.0;

        if (setupDirection != null && (signal == SignalType.BUY || signal == SignalType.SELL)) {
            log.info("V2.2 DIRECTION INTEGRITY | Symbol={} | Setup={} | Technical={} | Result=CONFIRMED",
                    request.getSymbol(), setupDirection, signal);
        } else if (setupDirection != null && signal == SignalType.HOLD) {
            log.debug("V2.2 DIRECTION INTEGRITY | Symbol={} | Setup={} | TechnicalEvidence=BUY:{} SELL:{} | Result=HOLD",
                    request.getSymbol(), setupDirection, buyScore, sellScore);
        }

        log.info("V2.2 TECHNICAL DECISION | Symbol={} | Setup={} | MarketRegime={} | BUY={} | SELL={} | Confidence={} | Signal={}",
                request.getSymbol(),
                setupDirection,
                request.getMarketContext() != null ? request.getMarketContext().getMarketTrend() : null,
                buyScore,
                sellScore,
                confidence,
                signal);

        if (log.isDebugEnabled()) {
            results.stream()
                    .filter(Objects::nonNull)
                    .forEach(result -> log.debug(
                            "V2.2 RULE | Symbol={} | Rule={} | Signal={} | Score={} | Max={} | Reason={}",
                            request.getSymbol(), result.getRuleName(), result.getSignal(),
                            result.getScore(), result.getMaxScore(), result.getReason()));
        }

        return TradingDecision.builder()
                .signal(signal)
                .confidence(confidence)
                .confidenceLevel(level)
                .reasons(collectReasons(results))
                .ruleResults(results)
                .build();
    }

    private SignalType determineSignal(List<RuleResult> results, SignalType setupDirection) {
        int buyScore = score(results, SignalType.BUY);
        int sellScore = score(results, SignalType.SELL);
        int totalPossibleScore = results.stream()
                .filter(Objects::nonNull)
                .mapToInt(RuleResult::getMaxScore)
                .sum();

        if (buyScore == 0 && sellScore == 0) {
            return SignalType.HOLD;
        }
        if (totalPossibleScore <= 0) {
            log.warn("V2.2 SIGNAL DECISION | Invalid total possible score={} | Decision=HOLD",
                    totalPossibleScore);
            return SignalType.HOLD;
        }

        int netScore = buyScore - sellScore;
        double dominanceRatio = Math.abs(netScore) / (double) totalPossibleScore;

        if (dominanceRatio < MINIMUM_DOMINANCE_RATIO) {
            return SignalType.HOLD;
        }

        SignalType technicalDirection;
        if (buyScore > sellScore) {
            technicalDirection = SignalType.BUY;
        } else if (sellScore > buyScore) {
            technicalDirection = SignalType.SELL;
        } else {
            return SignalType.HOLD;
        }

        // Strategy setup is a directional constraint. Technical rules confirm it;
        // they must not reverse it. This preserves JUDAS/ORB direction symmetry.
        if (setupDirection == SignalType.BUY || setupDirection == SignalType.SELL) {
            if (technicalDirection != setupDirection) {
                log.info("V2.2 DIRECTION CONFLICT | Setup={} | Technical={} | BUY={} | SELL={} | Decision=HOLD",
                        setupDirection, technicalDirection, buyScore, sellScore);
                return SignalType.HOLD;
            }
        }

        return technicalDirection;
    }

    private int score(List<RuleResult> results, SignalType signalType) {
        return results.stream()
                .filter(result -> result != null && result.getSignal() == signalType)
                .mapToInt(RuleResult::getScore)
                .sum();
    }

    private TradingDecision holdDecision(String reason) {
        return TradingDecision.builder()
                .signal(SignalType.HOLD)
                .confidence(0)
                .confidenceLevel(confidenceCalculator.determineLevel(0))
                .reasons(List.of(reason))
                .ruleResults(List.of())
                .build();
    }

    private List<String> collectReasons(List<RuleResult> results) {
        return results.stream()
                .filter(Objects::nonNull)
                .map(RuleResult::getReason)
                .toList();
    }
}
