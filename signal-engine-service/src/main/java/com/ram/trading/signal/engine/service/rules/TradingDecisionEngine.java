package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.ConfidenceLevel;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TradingDecisionEngine {

    private final RuleEngine ruleEngine;

    private final ConfidenceCalculator confidenceCalculator;

    public TradingDecision generateDecision(
            SignalGenerationRequest request) {

        List<RuleResult> results =
                ruleEngine.executeRules(request);

        SignalType signal =
                determineSignal(results);

        int confidence =
                confidenceCalculator.calculateScore(results);

        ConfidenceLevel level =
                confidenceCalculator.determineLevel(confidence);

        return TradingDecision.builder()
                .signal(signal)
                .confidence(confidence)
                .confidenceLevel(level)
                .reasons(collectReasons(results))
                .build();
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