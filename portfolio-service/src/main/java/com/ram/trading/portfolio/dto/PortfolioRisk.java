package com.ram.trading.portfolio.dto;

import com.ram.trading.portfolio.contant.RISKLEVELENUM;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioRisk {

    private RISKLEVELENUM riskLevel;

    private String message;
}