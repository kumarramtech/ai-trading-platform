package com.ram.trading.newsanalysis.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
public class MarketTrendAnalysisResponse {

    private String overallMarketSentiment;

    private String marketTrend;

    private List<String> positiveSectors;

    private List<String> negativeSectors;

    private List<String> trendingThemes;

    private String summary;
}