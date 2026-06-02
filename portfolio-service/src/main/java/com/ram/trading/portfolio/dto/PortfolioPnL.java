package com.ram.trading.portfolio.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioPnL {

    private String symbol;
    private Integer quantity;
    private Double averagePrice;
    private Double currentPrice;
    private Double profitLoss;
}