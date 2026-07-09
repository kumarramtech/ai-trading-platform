package com.ram.trading.ai.engine.dto.portfolio;

import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OpenPositionContextResponse {

    private boolean positionExists;

    private String symbol;

    private Integer quantity;

    private Double entryPrice;

    private Double currentPrice;

    private Double currentPnL;

    private Double pnlPercentage;

    private Double stopLoss;

    private Double targetPrice;

    private String status;

    private String signal;
}