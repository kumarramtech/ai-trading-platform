package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TradeInsightsResponse {

    private String bestPerformingSymbol;

    private Double bestPerformingProfit;

    private String worstPerformingSymbol;

    private Double worstPerformingProfit;

    private Double averageWinningConfidence;

    private Double averageLosingConfidence;

    private Double positiveSentimentWinRate;

    private Double negativeSentimentWinRate;

    private Integer totalClosedTrades;
}