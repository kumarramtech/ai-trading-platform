package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperTradeSummary {

    private long totalTrades;

    private long openPositions;

    private long closedPositions;

    private double totalInvestment;

    private double totalProfit;

    private double winRate;

    private Long winningTrades;

    private Long losingTrades;

    private Long breakevenTrades;
}