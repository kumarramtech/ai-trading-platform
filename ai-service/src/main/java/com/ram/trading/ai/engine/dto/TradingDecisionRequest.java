package com.ram.trading.ai.engine.dto;

import com.ram.trading.ai.engine.dto.portfolio.PortfolioContextResponse;
import lombok.*;


@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradingDecisionRequest {

    private SignalGenerationRequest signalRequest;

    private TradingDecision technicalDecision;

    private String newsSummary;

    private String sectorSummary;

    private PortfolioContextResponse portfolioContext;;

}