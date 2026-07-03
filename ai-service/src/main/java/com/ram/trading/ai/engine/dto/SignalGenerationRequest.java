package com.ram.trading.ai.engine.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalGenerationRequest {

    private String symbol;

    private Double currentPrice;

    private Double rsi;

    private Double ema20;

    private Double ema50;

    private Double sma20;

    private Double sma50;

    private Double macd;

    private Double signalLine;

    private Double atr;

    private Long volume;

    private MarketContext marketContext;

}