package com.ram.trading.backtesting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BacktestResult {

    private String symbol;

    private Double buyPrice;

    private Double sellPrice;

    private Double profit;
}