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
public class TrendingMarketSnapshot {

    private String tradingSymbol;

    private String companyName;

    private String instrumentKey;

    private Double lastPrice;

    private Double changePercentage;

    private Long volume;

    private Double open;

    private Double high;

    private Double low;

    private Double close;

    private Double averagePrice;

    private Long timestamp;
}