package com.ram.trading.signal.engine.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpenPositionResponse {

    private Long tradeId;

    private String symbol;

    private String signal;

    private Double entryPrice;

    private Double currentPrice;

    private Double targetPrice;

    private Double stopLoss;

    private Integer quantity;

    private Double investedAmount;

    private Double currentValue;

    private Double currentPnL;

    private Double pnlPercentage;

    private String status;

    private Double portfolioAllocation;

    private Double targetProgress;
}