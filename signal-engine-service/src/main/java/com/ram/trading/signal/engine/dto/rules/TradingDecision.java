package com.ram.trading.signal.engine.dto.rules;

import com.ram.trading.signal.engine.contant.ConfidenceLevel;
import com.ram.trading.signal.engine.contant.SignalType;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingDecision {

    private SignalType signal;

    private Integer confidence;

    private ConfidenceLevel confidenceLevel;

    private List<String> reasons;

}