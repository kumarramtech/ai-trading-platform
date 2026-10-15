package com.ram.trading.ai.engine.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class CacheKeyBuilder {

    private CacheKeyBuilder() {
    }

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules();

    public static String buildAiDecisionKey(TradingDecisionRequest request) {

        if (request == null
                || request.getSignalRequest() == null
                || request.getTechnicalDecision() == null
                || request.getTechnicalDecision().getSignal() == null
                || request.getTechnicalDecision().getConfidenceLevel() == null) {
            throw new IllegalArgumentException(
                    "Trading decision request and required decision fields must not be null");
        }

        String symbol = request.getSignalRequest().getSymbol();
        String normalizedSymbol =
                symbol == null || symbol.isBlank()
                        ? "NA"
                        : symbol.trim().toUpperCase(Locale.ROOT);

        /*
         * Hash the complete request instead of maintaining a manual list
         * of fields. This prevents prompt-relevant fields such as setup
         * direction, strategy, decision reasons, and additional market
         * context from being accidentally omitted from the cache key.
         *
         * This may cause a cache miss when a non-prompt field changes,
         * which is safer than reusing a decision for different inputs.
         */
        return CacheConstants.AI_DECISION
                + ":"
                + normalizedSymbol
                + ":"
                + stableHash(request);
    }

    private static String stableHash(Object value) {

        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(value);

            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(json);

            return HexFormat.of().formatHex(digest);

        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException(
                    "Unable to generate deterministic AI cache key",
                    ex);
        }
    }
}