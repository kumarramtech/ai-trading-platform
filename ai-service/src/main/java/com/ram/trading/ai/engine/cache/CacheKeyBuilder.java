package com.ram.trading.ai.engine.cache;

import com.ram.trading.ai.engine.dto.SignalGenerationRequest;
import com.ram.trading.ai.engine.dto.TradingDecision;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;

public final class CacheKeyBuilder {

    private CacheKeyBuilder(){}

    public static String buildAiDecisionKey(
            TradingDecisionRequest request) {

        SignalGenerationRequest signal =
                request.getSignalRequest();

        TradingDecision decision =
                request.getTechnicalDecision();

        return String.join(
                ":",
                CacheConstants.AI_DECISION,
                signal.getSymbol(),
                decision.getSignal().name(),
                decision.getConfidenceLevel().name(),
                format(signal.getRsi()),
                format(signal.getEma20()),
                format(signal.getEma50()),
                format(signal.getMacd()),
                format(signal.getCurrentPrice())
        );
    }

    private static String format(Double value) {

        if (value == null) {
            return "NA";
        }

        return String.format(
                java.util.Locale.US,
                "%.4f",
                value);
    }

}