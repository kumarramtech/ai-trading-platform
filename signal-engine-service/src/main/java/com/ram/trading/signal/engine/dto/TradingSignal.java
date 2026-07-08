package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TradingSignal {

    private String symbol;
    private String signal;
    private Double entryPrice;
    private Double targetPrice;
    private Double stopLoss;
    private String reason;
    private Integer confidence;
    private Double rsi;
    private Double ema20;
    private Double ema50;
    private Double macd;
    private Integer newsScore;
    private String newsSentiment;
    private String newsSummary;
    private String aiRecommendation;
    private String aiReasoning;
    private String riskLevel;
    private String exitStrategy;
    private String positionSize;
}