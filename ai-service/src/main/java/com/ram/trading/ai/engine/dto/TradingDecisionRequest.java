package com.ram.trading.ai.engine.dto;

import lombok.*;
import reactor.core.publisher.SignalType;

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

    private String portfolioSummary;

}