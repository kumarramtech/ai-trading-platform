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

    /**
     * Price that invalidates the setup (JUDAS: the sweep candle's extreme).
     * Null when the setup has no structural level of its own.
     */
    private Double setupInvalidationLevel;

    /** Confirmation-candle volume / opening-range average volume (JUDAS, ORB); null otherwise. */
    private Double setupVolumeRatio;

    /** The stock's % move since today's open (same basis as MarketContext.niftyChange); null when unknown. */
    private Double stockChangePct;

    /** SetupQualityScorer score 0-100, set once scored; null before that. */
    private Integer setupQualityScore;

}