package com.ram.trading.newsanalysis.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.newsanalysis.dto.MarketTrendAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MarketTrendResponseParser {

    private final ObjectMapper objectMapper;

    public MarketTrendAnalysisResponse parse(String aiResponse) {

        try {

            String json = extractJson(aiResponse);

            return objectMapper.readValue(
                    json,
                    MarketTrendAnalysisResponse.class
            );

        } catch (Exception ex) {

            return MarketTrendAnalysisResponse.builder()
                    .overallMarketSentiment("NEUTRAL")
                    .marketTrend("MIXED")
                    .positiveSectors(List.of())
                    .negativeSectors(List.of())
                    .trendingThemes(List.of())
                    .summary("Unable to analyze market trend")
                    .build();
        }
    }

    private String extractJson(String response) {

        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");

        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }

        return response;
    }
}