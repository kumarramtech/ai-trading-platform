package com.ram.trading.signal.engine.dto.portfolio;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioRisk {

    private RiskLevel riskLevel;

    private String message;
}