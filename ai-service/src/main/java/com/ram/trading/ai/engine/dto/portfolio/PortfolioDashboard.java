package com.ram.trading.ai.engine.dto.portfolio;

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