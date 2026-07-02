package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;

public interface SignalRule {

    RuleResult evaluate(SignalGenerationRequest request);

    String getRuleName();

}