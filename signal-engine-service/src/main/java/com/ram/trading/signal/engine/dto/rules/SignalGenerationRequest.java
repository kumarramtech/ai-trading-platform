package com.ram.trading.signal.engine.dto.rules;

import com.ram.trading.signal.engine.contant.SignalType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
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

    private Double previousMacd;

    private Double previousSignalLine;

    private Double atr;

    private Long volume;

    private MarketContext marketContext;

    private String setupStrategy;

    private SignalType setupDirection;

}