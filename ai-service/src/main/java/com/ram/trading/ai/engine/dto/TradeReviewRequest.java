package com.ram.trading.ai.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeReviewRequest {

    private Long tradeId;

    private String symbol;

    private String signal;

    private Double entryPrice;

    private Double exitPrice;

    private Double profitLoss;

    private Integer confidence;

    private Double rsi;

    private Double ema20;

    private Double ema50;

    private Double macd;
}