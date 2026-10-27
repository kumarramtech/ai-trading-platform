package com.ram.trading.market.data.dto;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

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

    /** Today's session open from the feed's 1d OHLC; openPrice is the I1 candle's open. */
    private Double dayOpenPrice;

    private Double change;

    private Double changePercentage;

    /**
     * Order book from the full feed, best level first (up to 5 levels); null for
     * indices and feeds without depth. Lets paper fills walk the real book.
     */
    private java.util.List<Double> bidPrices;

    private java.util.List<Long> bidQuantities;

    private java.util.List<Double> askPrices;

    private java.util.List<Long> askQuantities;

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