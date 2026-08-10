package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EngineeringFilterService {

    @Value("${trading.engineering.min-ai-confidence:60}")
    private int minAiConfidence;

    public boolean isEligibleForAI(
            TradingDecision decision){

        if (decision == null) {
            return false;
        }

        if (decision.getSignal() == null) {
            return false;
        }

        if (decision.getSignal() == SignalType.HOLD ||
                decision.getSignal() == SignalType.NEUTRAL) {
            return false;
        }

        return decision.getConfidence() != null
                && decision.getConfidence() >= minAiConfidence;
    }
}