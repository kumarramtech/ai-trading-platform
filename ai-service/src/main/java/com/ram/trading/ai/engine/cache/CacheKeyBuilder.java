package com.ram.trading.ai.engine.cache;

import com.ram.trading.ai.engine.dto.SignalGenerationRequest;
import com.ram.trading.ai.engine.dto.TradingDecision;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;

public final class CacheKeyBuilder {

    private CacheKeyBuilder(){}

    public static String buildAiDecisionKey(TradingDecisionRequest request) {

        SignalGenerationRequest signal = request.getSignalRequest();
        TradingDecision decision = request.getTechnicalDecision();

        return String.join(":",
                CacheConstants.AI_DECISION,
                signal.getSymbol(),
                decision.getSignal().name(),
                decision.getConfidenceLevel().name(),
                String.valueOf(signal.getRsi().intValue()),
                String.valueOf(signal.getCurrentPrice().intValue())
        );
    }

}