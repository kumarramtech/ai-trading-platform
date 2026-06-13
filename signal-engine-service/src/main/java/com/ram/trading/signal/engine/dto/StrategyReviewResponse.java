package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyReviewResponse {

    private Integer totalTrades;

    private Integer winningTrades;

    private Integer losingTrades;

    private String review;
}