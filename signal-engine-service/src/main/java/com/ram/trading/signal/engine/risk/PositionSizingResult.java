package com.ram.trading.signal.engine.risk;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PositionSizingResult {

    private final boolean valid;

    private final double entryPrice;

    private final double stopLoss;

    private final double riskPerShare;

    private final double maxRiskPerTrade;

    private final int riskBasedQuantity;

    private final double expectedRisk;

    private final String rejectionReason;
}