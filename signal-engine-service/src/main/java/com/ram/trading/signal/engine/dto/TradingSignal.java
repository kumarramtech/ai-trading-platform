package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TradingSignal {

    private String symbol;
    private String signal;
    private Double entryPrice;
    private Double targetPrice;
    private Double stopLoss;
    private String reason;
    private Integer confidence;
}