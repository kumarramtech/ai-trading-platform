package com.ram.trading.signal.engine.dto.ai;

import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
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

    private String portfolioSummary;

}