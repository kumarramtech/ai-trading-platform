package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TradeLifecycleDto {

    private Long opportunityId;

    private Long signalId;

    private Long tradeId;

    private String symbol;

    private String signal;

    private Integer confidence;

    private Integer newsScore;

    private String sentiment;

    private Double entryPrice;

    private Double targetPrice;

    private Double stopLoss;

    private Integer quantity;

    private Double investedAmount;

    private String tradeStatus;

    private Double profitLoss;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;
}