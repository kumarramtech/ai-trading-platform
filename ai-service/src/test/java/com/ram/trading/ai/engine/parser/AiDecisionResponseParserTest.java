package com.ram.trading.ai.engine.parser;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiDecisionResponseParserTest {

    // Mirrors Spring Boot's auto-configured mapper.
    private final AiDecisionResponseParser parser = new AiDecisionResponseParser(
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

    @Test
    void parsesTrailingCommaBeforeClosingBrace() {
        // Shape of the SOUTHBANK 2026-10-26 failure: line 8, column 3 inside "decision"
        String response = """
                {
                  "decision": {
                    "tradeAllowed": true,
                    "recommendation": "SELL",
                    "confidence": 71,
                    "decisionStrength": "HIGH",
                    "reason": "Engineering SELL confirmed.",
                  },
                  "aiReasoning": "Validated."
                }
                """;

        AiDecisionResponse parsed = parser.parse(response);

        assertEquals(AiRecommendation.SELL, parsed.getDecision().getRecommendation());
        assertTrue(parsed.getDecision().getTradeAllowed());
        assertEquals(71, parsed.getDecision().getConfidence());
    }

    @Test
    void stripsCodeFencesAndSurroundingProse() {
        String response = """
                Here is the decision:
                ```json
                {"decision": {"tradeAllowed": false, "recommendation": "HOLD", "confidence": 40}}
                ```
                Let me know if you need more.
                """;

        AiDecisionResponse parsed = parser.parse(response);

        assertEquals(AiRecommendation.HOLD, parsed.getDecision().getRecommendation());
        assertFalse(parsed.getDecision().getTradeAllowed());
    }

    @Test
    void stillRejectsGenuinelyBrokenJson() {
        assertThrows(RuntimeException.class,
                () -> parser.parse("{\"decision\": {\"recommendation\": \"SELL\" \"confidence\": 71}}"));
        assertThrows(RuntimeException.class, () -> parser.parse(null));
    }
}
