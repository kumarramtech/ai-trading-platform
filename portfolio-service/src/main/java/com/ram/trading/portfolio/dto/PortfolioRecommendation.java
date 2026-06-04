package com.ram.trading.portfolio.dto;

import com.ram.trading.portfolio.contant.RecommendationActionEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioRecommendation {

    private String symbol;

    private RecommendationActionEnum action;

    private String reason;
}