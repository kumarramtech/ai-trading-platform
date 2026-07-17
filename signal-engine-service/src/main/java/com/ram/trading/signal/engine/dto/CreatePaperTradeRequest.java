package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaperTradeRequest {

    private String symbol;

    private String paperSignal;

    private Double entryPrice;

    private Integer quantity;

    private Double targetPrice;

    private Double stopLoss;

    private Integer confidence;

    private Double ema20;

    private Double ema50;

    private Double macd;

    private Double rsi;

}