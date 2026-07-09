package com.ram.trading.signal.engine.dto.portfolio;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioSummary {

    private Double totalInvested;

    private Double currentValue;

    private Double totalProfitLoss;
}