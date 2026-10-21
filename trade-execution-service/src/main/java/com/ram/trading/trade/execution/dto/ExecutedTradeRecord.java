package com.ram.trading.trade.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutedTradeRecord {

    private Long id;

    private Long signalId;

    private String symbol;

    private String instrumentKey;

    private String side;

    private Integer requestedQuantity;

    private Integer filledQuantity;

    private BigDecimal averageEntryPrice;

    private BigDecimal initialStopLoss;

    private BigDecimal currentStopLoss;

    private BigDecimal targetPrice;

    /** True once the optional target extension has been committed. */
    private Boolean targetExtended;

    private String status;

    private String entryOrderId;

    private String exitOrderId;

    private BigDecimal exitPrice;

    private BigDecimal realizedPnl;

    private String exitReason;

    private LocalDateTime openedAt;

    private LocalDateTime closedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer exitFilledQuantity;

    private Integer remainingQuantity;

    private BigDecimal exitAveragePrice;

    private Integer exitOrderQuantity;

    /** Monotonically increasing identity for each newly claimed exit attempt. */
    private Integer exitAttempt;
}
