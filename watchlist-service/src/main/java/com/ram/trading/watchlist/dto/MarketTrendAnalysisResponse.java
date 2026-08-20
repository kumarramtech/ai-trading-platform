package com.ram.trading.watchlist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketTrendAnalysisResponse {

    private String overallMarketSentiment;

    private String marketTrend;

    private List<String> positiveSectors;

    private List<String> negativeSectors;

    private List<String> trendingThemes;

    private String summary;
}