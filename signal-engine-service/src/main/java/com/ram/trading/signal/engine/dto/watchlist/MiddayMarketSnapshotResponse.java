package com.ram.trading.signal.engine.dto.watchlist;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MiddayMarketSnapshotResponse {

    private Instant timestamp;

    private List<TrendingStockResponse> topGainers;

    private List<TrendingStockResponse> topLosers;

    private List<TrendingStockResponse> trendingStocks;

    private int totalCandidates;
}