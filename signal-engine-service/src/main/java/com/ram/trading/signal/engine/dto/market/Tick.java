package com.ram.trading.signal.engine.dto.market;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Builder
@Data
public class Tick {

    private String exchange;

    private String symbol;

    private String instrumentKey;

    private Double lastTradedPrice;

    private Double openPrice;

    private Double highPrice;

    private Double lowPrice;

    private Double closePrice;

    private Long volume;

    private Long timestamp;

    private Double previousClose;

    /** Today's session open (market-stream sends it); openPrice is the 1-minute candle's open. */
    private Double dayOpenPrice;

    /** Order book from the full feed, best level first (up to 5); null without depth. */
    private java.util.List<Double> bidPrices;

    private java.util.List<Long> bidQuantities;

    private java.util.List<Double> askPrices;

    private java.util.List<Long> askQuantities;

    private Double change;

    private Double changePercentage;

    /** Market-wide context enriched by the market feed. */
    private Double niftyChange;

    private Double bankNiftyChange;

    private String marketRegime;

    public LocalDateTime getTradeTime() {
        if (timestamp == null) {
            return null;
        }

        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.of("Asia/Kolkata"));
    }

}