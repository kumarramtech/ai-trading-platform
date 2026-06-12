package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SignalExplanationRequest {

    private String symbol;
    private String signal;
    private Integer confidence;
    private Double rsi;
    private Double ema20;
    private Double ema50;
    private Double macd;
}