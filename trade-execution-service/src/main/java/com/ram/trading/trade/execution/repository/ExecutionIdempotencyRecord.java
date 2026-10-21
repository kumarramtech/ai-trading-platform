package com.ram.trading.trade.execution.repository;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ExecutionIdempotencyRecord {
    private Long signalId;
    private String tag;
    private String symbol;
    private String signal;
    private Integer quantity;
    private String status;
    private String orderId;
    private Integer filledQuantity;
    private BigDecimal averagePrice;
    private String rejectionReason;
    private String instrumentKey;
    private BigDecimal entryPrice;
    private BigDecimal requiredMargin;
    private BigDecimal stopLoss;
    private BigDecimal target;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
