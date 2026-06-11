package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradePerformance {

    private long totalTrades;

    private long winningTrades;

    private long losingTrades;

    private double averageProfit;

    private double averageLoss;

    private double bestTrade;

    private double worstTrade;

    private double winRate;
}