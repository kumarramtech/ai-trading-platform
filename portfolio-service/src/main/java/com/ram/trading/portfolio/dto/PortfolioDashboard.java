package com.ram.trading.portfolio.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioDashboard {

    private PortfolioSummary summary;

    private PortfolioRisk risk;

    private List<PortfolioAllocation> allocations;
}