package com.ram.trading.market.data.dto;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class MarketTick {

    private String instrumentKey;

    private String symbol;

    private double lastPrice;

    private double previousClose;

    private int lastTradeQuantity;

    private long lastTradeTime;

    private double change;

    private double changePercentage;

    private FeedType feedType;

}