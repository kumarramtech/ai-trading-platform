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

    private Double change;

    private Double changePercentage;

    public LocalDateTime getTradeTime() {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.of("Asia/Kolkata"));
    }

}