package com.ram.trading.portfolio.dto;

import com.ram.trading.portfolio.entity.Portfolio;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSnapshot {

    private List<Portfolio> portfolios;

    private Map<String, StockResponse> stockPrices;
}