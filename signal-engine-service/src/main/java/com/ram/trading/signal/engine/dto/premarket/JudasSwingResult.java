package com.ram.trading.signal.engine.dto.premarket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudasSwingResult {

    private String symbol;

    private boolean detected;

    /**
     * BULLISH_REVERSAL / BEARISH_REVERSAL / NONE
     */
    private String direction;

    /**
     * HIGH_SWEEP / LOW_SWEEP / NONE
     */
    private String sweepSide;

    private Double openingRangeHigh;

    private Double openingRangeLow;

    private Double sweepPrice;

    private Double confirmationPrice;

    private Double confirmationVolume;

    private Double averageVolume;

    /**
     * Candle where the initial sweep occurred.
     */
    private LocalDateTime sweepTime;

    /**
     * Candle that confirmed the reversal.
     */
    private LocalDateTime confirmationTime;

    /**
     * Human-readable explanation for logs/debugging.
     */
    private String reason;
}