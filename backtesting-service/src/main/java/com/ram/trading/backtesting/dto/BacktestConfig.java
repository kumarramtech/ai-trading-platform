package com.ram.trading.backtesting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BacktestConfig {

    private Double buyDropPercent;
    private Double targetProfitPercent;
    private Double stopLossPercent;
}