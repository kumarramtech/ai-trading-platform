package com.ram.trading.signal.engine.dto.rules;

import com.ram.trading.signal.engine.contant.ConfidenceLevel;
import com.ram.trading.signal.engine.contant.SignalType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Builder
@Getter
@Setter
public class TradingSignalResponse {

    private String symbol;

    private SignalType signal;

    private Integer confidence;

    private ConfidenceLevel confidenceLevel;

    private List<String> reasons;

    private LocalDateTime generatedTime;

    private TradeExecutionPlan executionPlan;

}