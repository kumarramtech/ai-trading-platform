package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuleEngine {

    private final List<SignalRule> signalRules;

    public List<RuleResult> executeRules(
            SignalGenerationRequest request) {

        List<RuleResult> results = new ArrayList<>();

        for (SignalRule rule : signalRules) {

            log.debug("Executing Rule : {}", rule.getRuleName());

            RuleResult result = rule.evaluate(request);

            log.info(
                    "Rule: {}, Signal: {}, Score: {}, Reason: {}",
                    rule.getRuleName(),
                    result.getSignal(),
                    result.getScore(),
                    result.getReason());

            results.add(result);
        }

        return results;
    }

}