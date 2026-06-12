package com.ram.trading.ai.engine.dto;

import lombok.Data;

@Data
public class SignalExplanationRequest {

    private String symbol;

    private String signal;

    private Integer confidence;

    private Double rsi;

    private Double ema20;

    private Double ema50;

    private Double macd;
}