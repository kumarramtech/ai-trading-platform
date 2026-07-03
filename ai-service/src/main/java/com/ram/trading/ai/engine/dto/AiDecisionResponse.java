package com.ram.trading.ai.engine.dto;

import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.decision.Decision;
import com.ram.trading.ai.engine.dto.execution.ExecutionPlan;
import com.ram.trading.ai.engine.dto.news.NewsAnalysis;
import com.ram.trading.ai.engine.dto.portfolio.PortfolioAnalysis;
import com.ram.trading.ai.engine.dto.risk.RiskAnalysis;
import com.ram.trading.ai.engine.dto.technical.TechnicalAnalysis;
import lombok.*;

import java.util.List;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AiDecisionResponse {

    private Decision decision;

    private TechnicalAnalysis technicalAnalysis;

    private RiskAnalysis riskAnalysis;

    private NewsAnalysis newsAnalysis;

    private PortfolioAnalysis portfolioAnalysis;

    private ExecutionPlan executionPlan;

    private String aiReasoning;

}