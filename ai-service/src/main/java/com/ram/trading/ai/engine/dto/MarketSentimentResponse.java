package com.ram.trading.ai.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketSentimentResponse {

    private String symbol;

    private String sentiment;

    private Integer sentimentScore;

    private String reason;
}