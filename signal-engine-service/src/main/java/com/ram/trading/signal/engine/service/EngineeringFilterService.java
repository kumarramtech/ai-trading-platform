package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import org.springframework.stereotype.Service;

@Service
public class EngineeringFilterService {

    private static final int MIN_AI_CONFIDENCE = 60;

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
                && decision.getConfidence() >= MIN_AI_CONFIDENCE;
    }
}