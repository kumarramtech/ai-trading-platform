package com.ram.trading.backtesting.dto;

public record StrategyResult(

        Double buy,
        Double target,
        Double stop,

        Integer trades,
        Double winRate,
        Double profit

) {}