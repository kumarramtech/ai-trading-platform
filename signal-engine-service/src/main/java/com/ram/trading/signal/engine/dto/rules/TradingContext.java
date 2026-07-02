package com.ram.trading.signal.engine.dto.rules;

import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradingContext {

    private SignalGenerationRequest signalRequest;

    private TradingDecision technicalDecision;

    private TradeExecutionPlan executionPlan;

    private AiDecision aiDecision;

}