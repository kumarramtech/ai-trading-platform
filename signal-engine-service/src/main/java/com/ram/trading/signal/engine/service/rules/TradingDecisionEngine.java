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

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingDecisionEngine {

    private final RuleEngine ruleEngine;

    private final RuleConfidenceCalculator confidenceCalculator;

    public TradingDecision generateDecision(
            SignalGenerationRequest request) {

        log.info("======================================================");
        log.info("Generating Technical Decision for {}", request.getSymbol());
        log.info("======================================================");

        List<RuleResult> results =
                ruleEngine.executeRules(request);

        log.info("Rule Engine returned {} results", results.size());

        SignalType signal =
                determineSignal(results);

        int confidence =
                confidenceCalculator.calculateScore(results);

        ConfidenceLevel level =
                confidenceCalculator.determineLevel(confidence);

        log.info("Technical Decision Summary");
        log.info("Symbol            : {}", request.getSymbol());
        log.info("Final Signal      : {}", signal);
        log.info("Confidence        : {}", confidence);
        log.info("Confidence Level  : {}", level);

        TradingDecision decision =
                TradingDecision.builder()
                        .signal(signal)
                        .confidence(confidence)
                        .confidenceLevel(level)
                        .reasons(collectReasons(results))
                        .ruleResults(results)
                        .build();

        log.info("Technical Decision Created Successfully");
        log.info("======================================================");

        return decision;
    }

    private SignalType determineSignal(
            List<RuleResult> results) {

        long buy = results.stream()
                .filter(r -> r.getSignal() == SignalType.BUY)
                .count();

        long sell = results.stream()
                .filter(r -> r.getSignal() == SignalType.SELL)
                .count();

        if (buy > sell)
            return SignalType.BUY;

        if (sell > buy)
            return SignalType.SELL;

        return SignalType.HOLD;
    }

    private List<String> collectReasons(
            List<RuleResult> results) {

        return results.stream()
                .map(RuleResult::getReason)
                .toList();
    }

}