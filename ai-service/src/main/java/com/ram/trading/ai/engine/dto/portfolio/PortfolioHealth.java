package com.ram.trading.ai.engine.dto.portfolio;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioHealth {

    private Integer score;

    private PortfolioHealthStatus status;
}