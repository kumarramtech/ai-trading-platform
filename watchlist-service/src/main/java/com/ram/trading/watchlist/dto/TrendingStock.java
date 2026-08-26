package com.ram.trading.watchlist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendingStock {

    private String tradingSymbol;

    private String companyName;

    private String instrumentKey;

    private Double lastPrice;

    private Double changePercentage;

    private Long volume;

    /**
     * Overall trending score from 0 to 100.
     */
    private Double trendingScore;

    /**
     * BULLISH / BEARISH / NEUTRAL
     */
    private String trendDirection;

    /**
     * STRONG_TREND / TRENDING / WATCH / IGNORE
     */
    private String trendingLevel;
}