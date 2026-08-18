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
public class ExecuteTradeResponse {

    private boolean success;

    private String tradeReferenceId;

    private String symbol;

    private String signal;

    private Integer quantity;

    private BigDecimal executedPrice;

    private BigDecimal requiredMargin;

    private String orderId;

    private String message;
}