package com.ram.trading.portfolio.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioContextResponse {

    private PortfolioSummary summary;

    private PortfolioRisk risk;

    private PortfolioHealth health;

    private List<PortfolioAllocation> allocations;

    private List<PortfolioRecommendation> recommendations;
}