package com.ram.trading.newsanalysis.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.newsanalysis.dto.SectorStockAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SectorStockResponseParser {

    private final ObjectMapper objectMapper;

    public SectorStockAnalysisResponse parse(String aiResponse) {

        try {

            String json = extractJson(aiResponse);

            return objectMapper.readValue(
                    json,
                    SectorStockAnalysisResponse.class
            );

        } catch (Exception ex) {

            log.error(
                    "Unable to parse sector stock analysis response",
                    ex
            );

            return SectorStockAnalysisResponse.builder()
                    .sectorStocks(java.util.List.of())
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