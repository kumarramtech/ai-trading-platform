package com.ram.trading.signal.engine.dto;

import lombok.Builder;

@Builder
public record SignalStats(

        long totalSignals,

        long openSignals,

        long winningSignals,

        long losingSignals,

        double winRate,

        double totalProfit
) {
}