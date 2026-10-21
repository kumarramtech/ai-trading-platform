package com.ram.trading.trade.execution.dto;

import java.math.BigDecimal;

/** Request to persist safer stop-loss and/or target levels for an OPEN trade. */
public record TradeProtectionUpdateRequest(
        Long signalId,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        Boolean targetExtended) {

    /** Backwards-compatible constructor for ordinary protection updates. */
    public TradeProtectionUpdateRequest(
            Long signalId, BigDecimal stopLoss, BigDecimal targetPrice) {
        this(signalId, stopLoss, targetPrice, null);
    }
}
