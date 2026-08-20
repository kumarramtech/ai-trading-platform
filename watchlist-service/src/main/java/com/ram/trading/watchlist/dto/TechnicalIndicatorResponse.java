package com.ram.trading.watchlist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalIndicatorResponse {

    private Long id;

    private String symbol;

    private Double closePrice;

    private Double rsi14;

    private Double sma20;

    private Double sma50;

    private Double ema20;

    private Double ema50;

    private Double macd;

    private Double signalLine;

    private Double volumeRatio;

    private Double previousMacd;

    private Double previousSignalLine;
}