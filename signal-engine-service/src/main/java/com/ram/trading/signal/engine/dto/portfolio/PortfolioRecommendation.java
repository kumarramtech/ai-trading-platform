package com.ram.trading.signal.engine.dto.portfolio;

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