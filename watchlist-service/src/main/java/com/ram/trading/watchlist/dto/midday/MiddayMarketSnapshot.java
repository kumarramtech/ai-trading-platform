package com.ram.trading.watchlist.dto.midday;

import com.ram.trading.watchlist.dto.TrendingStock;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class MiddayMarketSnapshot {

    Instant timestamp;

    List<TrendingStock> topGainers;

    List<TrendingStock> topLosers;

    List<TrendingStock> trendingStocks;

    int totalCandidates;
}