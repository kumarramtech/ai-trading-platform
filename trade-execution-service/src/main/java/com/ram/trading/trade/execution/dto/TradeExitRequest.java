
package com.ram.trading.trade.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to exit part or all of an existing executed trade.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeExitRequest {

    /** Signal ID of the existing executed trade. */
    private Long signalId;

    /** Quantity to exit, validated against the remaining position. */
    private Integer quantity;

    /** Optional exit order type; service applies the configured default. */
    private String orderType;
}
