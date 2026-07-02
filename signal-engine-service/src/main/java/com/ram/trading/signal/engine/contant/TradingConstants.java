package com.ram.trading.signal.engine.contant;

public final class TradingConstants {

    private TradingConstants() {}

    // RSI
    public static final double RSI_OVERSOLD = 30.0;
    public static final double RSI_OVERBOUGHT = 70.0;
    // Scores
    public static final int RSI_SCORE = 20;
    public static final int EMA_SCORE = 25;
    public static final int MACD_SCORE = 25;
    public static final int SMA_SCORE = 15;
    public static final int ATR_SCORE = 10;
    public static final int VOLUME_SCORE = 5;
    // Confidence
    public static final int HIGH_CONFIDENCE = 80;
    public static final int MEDIUM_CONFIDENCE = 60;
}