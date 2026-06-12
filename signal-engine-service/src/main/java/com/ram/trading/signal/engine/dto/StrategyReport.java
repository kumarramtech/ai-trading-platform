package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StrategyReport {

    private long totalTrades;

    private long winningTrades;

    private long losingTrades;

    private double winRate;

    private double averageConfidence;

    private double averageWinningConfidence;

    private double averageLosingConfidence;

    private double averageWinningRsi;

    private double averageLosingRsi;

    private long breakevenTrades;
}