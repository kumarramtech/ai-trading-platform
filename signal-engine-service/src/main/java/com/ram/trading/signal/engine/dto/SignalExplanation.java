package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SignalExplanation {

    private String symbol;

    private String signal;

    private Integer confidence;

    private String explanation;
}