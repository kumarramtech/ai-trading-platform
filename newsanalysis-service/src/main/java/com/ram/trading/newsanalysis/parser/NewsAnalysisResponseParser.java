package com.ram.trading.newsanalysis.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.newsanalysis.dto.NewsAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NewsAnalysisResponseParser {

    private final ObjectMapper objectMapper;

    public NewsAnalysisResponse parse(String symbol, String aiResponse) {

        try {
            aiResponse = clean(aiResponse);
            JsonNode node = objectMapper.readTree(aiResponse);

            return NewsAnalysisResponse.builder()
                    .symbol(symbol)
                    .sentiment(node.path("sentiment")
                            .asText("NEUTRAL"))
                    .score(node.path("score")
                            .asInt(50))
                    .summary(node.path("summary")
                            .asText("No summary available"))
                    .build();
        } catch (Exception ex) {
            log.error("Unable to parse AI response", ex);
            return NewsAnalysisResponse.builder()
                    .symbol(symbol)
                    .sentiment("NEUTRAL")
                    .score(50)
                    .summary("Unable to analyze market news.")
                    .build();
        }

    }

    private String clean(String response) {

        if (response == null) {
            return "";
        }

        return response

                .replace("```json", "")

                .replace("```", "")

                .trim();

    }

}