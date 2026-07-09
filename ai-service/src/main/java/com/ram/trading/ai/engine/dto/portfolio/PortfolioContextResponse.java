package com.ram.trading.ai.engine.dto.portfolio;

import lombok.*;

import java.util.List;

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