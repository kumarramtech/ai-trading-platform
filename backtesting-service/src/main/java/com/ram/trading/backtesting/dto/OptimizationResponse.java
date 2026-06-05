package com.ram.trading.backtesting.dto;

import java.util.List;

public record OptimizationResponse(

        long combinationsTested,

        StrategyResult bestStrategy,

        List<StrategyResult> topStrategies

) {}