package com.ram.trading.portfolio.dto;

import com.ram.trading.portfolio.contant.PortfolioHealthStatus;
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