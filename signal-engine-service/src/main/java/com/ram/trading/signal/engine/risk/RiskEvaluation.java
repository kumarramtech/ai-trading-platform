package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.decision.Decision;
import com.ram.trading.signal.engine.service.context.TradingContext;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
@Getter
@Setter
public class RiskEvaluation {

    private TradingContext context;

    private Decision decision;

    private TradingSignal signal;

}