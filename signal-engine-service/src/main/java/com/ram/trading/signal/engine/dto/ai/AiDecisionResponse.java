package com.ram.trading.signal.engine.dto.ai;


import com.ram.trading.signal.engine.dto.ai.decision.Decision;
import com.ram.trading.signal.engine.dto.ai.execution.ExecutionPlan;
import com.ram.trading.signal.engine.dto.ai.news.NewsAnalysis;
import com.ram.trading.signal.engine.dto.ai.portfolio.PortfolioAnalysis;
import com.ram.trading.signal.engine.dto.ai.risk.RiskAnalysis;
import com.ram.trading.signal.engine.dto.ai.technical.TechnicalAnalysis;
import lombok.*;

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