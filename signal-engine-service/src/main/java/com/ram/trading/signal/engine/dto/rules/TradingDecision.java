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

    private List<RuleResult> ruleResults;

    /** Set when a filter turned a technically valid setup into HOLD (e.g. REGIME_GUARD); shadow-tracked. */
    private String filterRejection;

    /** True when a counter-regime ORB was allowed on its own relative strength (tagged ORB_RS). */
    private boolean counterTrendException;
}