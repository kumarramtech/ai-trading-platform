
package com.ram.trading.trade.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeExecutionRequest {

    private Long signalId;

    private String symbol;

    private String instrumentKey;

    /**
     * BUY or SELL, approved by Signal Service.
     */
    private String signal;

    /**
     * Expected entry price from Signal Service.
     * Actual execution price must come from broker fills.
     */
    private BigDecimal entryPrice;

    private Integer quantity;

    private BigDecimal requiredMargin;

    /**
     * Original protective stop-loss from the approved signal.
     */
    private BigDecimal stopLoss;

    /**
     * Target price from the approved signal.
     */
    private BigDecimal target;

    /**
     * Optional entry order type; defaults to MARKET.
     */
    private String orderType;
}
