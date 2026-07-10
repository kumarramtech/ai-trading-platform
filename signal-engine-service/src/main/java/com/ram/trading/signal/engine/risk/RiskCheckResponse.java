package com.ram.trading.signal.engine.risk;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Builder
@Getter
@Setter
public class RiskCheckResponse {

    private boolean allowed;

    private List<String> violations;
}