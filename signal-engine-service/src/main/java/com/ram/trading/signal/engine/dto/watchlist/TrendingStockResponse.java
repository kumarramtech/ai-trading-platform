package com.ram.trading.signal.engine.dto.watchlist;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrendingStockResponse {

    private String tradingSymbol;

    private String companyName;

    private String instrumentKey;

    private Double lastPrice;

    private Double changePercentage;

    private Long volume;

    private Double trendingScore;

    private String trendDirection;

    private String trendingLevel;
}