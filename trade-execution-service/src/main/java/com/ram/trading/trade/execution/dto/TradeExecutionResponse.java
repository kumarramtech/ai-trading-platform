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
public class TradeExecutionResponse {

    private boolean success;

    private Long signalId;

    private String symbol;

    private String signal;

    private Integer quantity;

    private BigDecimal executedPrice;

    private BigDecimal reservedMargin;

    private String orderId;

    private String status;

    private String message;
}