package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class TradingSummary {

    private Long totalTrades;
    private Long openTrades;
    private Long closedTrades;

    private Long winningTrades;
    private Long losingTrades;
    private Long breakevenTrades;

    private Double winRate;

    private Double totalProfit;
    private Double todayProfit;

    private Double averageProfit;
    private Double averageLoss;

    private Double bestTrade;
    private Double worstTrade;
}