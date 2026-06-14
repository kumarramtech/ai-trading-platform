package com.ram.trading.signal.engine.dto;

import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradingDashboardResponse {

    private Long totalTrades;

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

    private Long openTrades;
}