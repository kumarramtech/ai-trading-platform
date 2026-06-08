package com.ram.trading.signal.engine.dto;

import lombok.Data;

@Data
public class TechnicalIndicatorResponse {

    private Long id;

    private String symbol;

    private Double closePrice;

    private Double rsi14;

    private Double ema20;

    private Double ema50;

    private Double macd;
}