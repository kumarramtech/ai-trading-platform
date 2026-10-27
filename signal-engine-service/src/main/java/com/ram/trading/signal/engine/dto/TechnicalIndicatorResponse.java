package com.ram.trading.signal.engine.dto;

import lombok.Data;

@Data
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

    // Strategy V2
    private Double previousMacd;
    private Double previousSignalLine;

    /** Average daily traded value (close x volume, rupees) over the last 20 sessions; null without volume data. */
    private Double averageDailyTurnover;
}