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
public class ORBResult {

    private String symbol;

    /**
     * true when a complete ORB setup is confirmed.
     */
    private boolean valid;

    /**
     * BULLISH / BEARISH / NONE
     */
    private String direction;

    /**
     * HIGH_BREAKOUT / LOW_BREAKOUT / NONE
     */
    private String breakoutSide;

    private Double openingRangeHigh;

    private Double openingRangeLow;

    /**
     * Price at the initial breakout.
     */
    private Double breakoutPrice;

    /**
     * Price/level tested during retest.
     */
    private Double retestPrice;

    /**
     * Final confirmation candle close.
     */
    private Double confirmationPrice;

    /**
     * Confirmation candle volume.
     */
    private Long confirmationVolume;

    /**
     * Average volume of the opening-range candles.
     */
    private Double averageOpeningRangeVolume;

    /**
     * Confirmation volume / average opening-range volume.
     */
    private Double volumeRatio;

    private LocalDateTime breakoutTime;

    private LocalDateTime retestTime;

    private LocalDateTime confirmationTime;

    /**
     * Explanation suitable for logs/debugging.
     */
    private String reason;
}