package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PerformanceAnalytics {

    private Integer totalTrades;

    private Integer openTrades;

    private Integer winningTrades;

    private Integer losingTrades;

    private Double winRate;

    private Double totalProfit;

    private Double totalLoss;

    private Double netProfit;

    private Double averageProfit;

    private Double averageLoss;

    private Double profitFactor;
}