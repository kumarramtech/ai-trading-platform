package com.ram.trading.signal.engine.dto;

import lombok.Data;

@Data
public class SignalExplanationResponse {

    private String symbol;
    private String signal;
    private Integer confidence;
    private String explanation;
}