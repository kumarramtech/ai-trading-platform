package com.ram.trading.signal.engine.risk;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
@Getter
@Setter
public class RiskViolation {

    private boolean passed;

    private String rule;

    private String reason;

}