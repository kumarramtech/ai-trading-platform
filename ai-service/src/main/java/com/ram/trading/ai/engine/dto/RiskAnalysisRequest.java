package com.ram.trading.ai.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAnalysisRequest {

    private String symbol;

    private String signal;

    private Integer confidence;

    private Double rsi;

    private Double ema20;

    private Double ema50;

    private Double macd;
}