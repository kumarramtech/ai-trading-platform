package com.ram.trading.ai.engine.parser;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiDecisionResponseParser {

    private final ObjectMapper objectMapper;

    public AiDecisionResponse parse(String response) {

        String json = cleanResponse(response);

        try {

            /*
             * LLMs occasionally emit a trailing comma before a closing
             * brace (e.g. SOUTHBANK 2026-10-26), which blocked an
             * otherwise valid decision. Tolerate it without accepting
             * any other malformed JSON.
             */
            return objectMapper
                    .readerFor(AiDecisionResponse.class)
                    .with(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature())
                    .readValue(json);

        } catch (Exception ex) {

            log.error("Unable to parse AI response. Response={}", abbreviate(json), ex);

            throw new RuntimeException(
                    "Invalid AI JSON Response.",
                    ex);

        }

    }

    private String cleanResponse(String response) {

        if (response == null) {
            return "";
        }

        String cleaned = response

                .replace("```json", "")

                .replace("```", "")

                .trim();

        /*
         * Drop any prose before the first '{' or after the last '}'.
         */
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned;

    }

    private String abbreviate(String value) {

        return value.length() <= 1000
                ? value
                : value.substring(0, 1000) + "...";

    }

}
