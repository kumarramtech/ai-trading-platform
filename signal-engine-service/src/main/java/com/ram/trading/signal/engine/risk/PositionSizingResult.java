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

    /** Maximum monetary loss trigger. It is NOT used to size entry quantity. */
    private final double maxRiskPerTrade;

    /** Kept for backward compatibility with existing callers. */
    private final int riskBasedQuantity;

    /** Final quantity calculated from broker margin and target margin. */
    private final int marginBasedQuantity;

    /** Theoretical loss if the original technical stop is hit. */
    private final double expectedRisk;

    private final double targetMargin;
    private final Double requiredMargin;
    private final Double tradeValue;
    private final Double leverage;
    private final Double availableBalance;

    private final String rejectionReason;
}
