package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ClosedPositionDto {

    private Long tradeId;

    private String symbol;

    private Double entryPrice;

    private Double exitPrice;

    private Integer quantity;

    private String status;

    private Double profitLoss;

    private LocalDateTime closedAt;

    private Long signalId;

    private String signal;

    private Double investedAmount;

    private Double targetPrice;

    private Double stopLoss;

    private Integer confidence;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;
}