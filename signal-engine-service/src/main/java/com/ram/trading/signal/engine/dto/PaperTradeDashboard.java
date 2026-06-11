package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaperTradeDashboard {

    private long totalTrades;

    private long openTrades;

    private long closedTrades;

    private long winningTrades;

    private long losingTrades;

    private long breakevenTrades;

    private double winRate;

    private double totalProfit;

    private double bestTrade;

    private double worstTrade;
}