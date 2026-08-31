package com.ram.trading.signal.engine.dto.premarket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpeningRange {

    private String symbol;

    private LocalDate tradingDate;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Double open;

    private Double high;

    private Double low;

    private Double close;

    private Long volume;

    /**
     * Absolute opening-range size.
     *
     * Example:
     *
     * High = 105
     * Low  = 100
     *
     * Range = 5
     */
    private Double range;

    /**
     * Opening range as percentage of opening price.
     */
    private Double rangePercentage;

    /**
     * Number of one-minute candles used.
     */
    private int candleCount;

    /**
     * Whether the complete 09:15-09:30
     * opening range was successfully formed.
     */
    private boolean complete;
}