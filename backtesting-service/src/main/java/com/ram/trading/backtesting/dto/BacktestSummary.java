package com.ram.trading.backtesting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BacktestSummary {

    private String symbol;

    private Integer totalTrades;

    private Integer winningTrades;

    private Integer losingTrades;

    private Double winRate;

    private Double netProfit;
}