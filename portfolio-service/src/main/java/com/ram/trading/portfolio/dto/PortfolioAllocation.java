package com.ram.trading.portfolio.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioAllocation {

    private String symbol;

    private Double currentValue;

    private Double allocationPercentage;
}