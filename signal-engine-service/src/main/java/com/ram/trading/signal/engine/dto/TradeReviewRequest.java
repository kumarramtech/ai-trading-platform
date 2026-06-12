package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
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