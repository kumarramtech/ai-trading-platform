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
public class MinuteCandle {

    private String symbol;

    /**
     * Start of the one-minute candle in IST.
     */
    private LocalDateTime minute;

    private Double open;

    private Double high;

    private Double low;

    private Double close;

    /**
     * Accumulated tick volume for this minute.
     */
    private Long volume;
}