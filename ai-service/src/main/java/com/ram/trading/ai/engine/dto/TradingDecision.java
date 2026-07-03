package com.ram.trading.ai.engine.dto;


import com.ram.trading.ai.engine.constant.enums.ConfidenceLevel;
import com.ram.trading.ai.engine.constant.enums.SignalType;
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