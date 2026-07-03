package com.ram.trading.ai.engine.parser;

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

        try {

            String json = cleanResponse(response);

            return objectMapper.readValue(
                    json,
                    AiDecisionResponse.class);

        } catch (Exception ex) {

            log.error("Unable to parse AI response.", ex);

            throw new RuntimeException(
                    "Invalid AI JSON Response.",
                    ex);

        }

    }

    private String cleanResponse(String response) {

        if (response == null) {
            return "";
        }

        return response

                .replace("```json", "")

                .replace("```", "")

                .trim();

    }

}