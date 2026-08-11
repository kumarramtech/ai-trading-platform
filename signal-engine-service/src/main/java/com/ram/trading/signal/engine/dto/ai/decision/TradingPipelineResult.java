package com.ram.trading.signal.engine.dto.ai.decision;

import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import com.ram.trading.signal.engine.service.context.TradingContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradingPipelineResult {

    private TradingDecision technicalDecision;

    private TradingContext tradingContext;

    private AiDecisionResponse aiDecision;

    private String skipReason;
}