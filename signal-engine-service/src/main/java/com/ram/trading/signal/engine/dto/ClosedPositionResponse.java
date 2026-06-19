package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClosedPositionResponse {

    private Integer totalTrades;

    private Integer winningTrades;

    private Integer losingTrades;

    private Double winRate;

    private Double totalProfit;

    private Double totalLoss;

    private Double netProfit;

    private Double averageProfit;

    private Double averageLoss;

    private Double profitFactor;

    private List<ClosedPositionDto> positions;
}