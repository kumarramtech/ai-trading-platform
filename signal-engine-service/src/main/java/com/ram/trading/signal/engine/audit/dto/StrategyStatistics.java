package com.ram.trading.signal.engine.audit.dto;

import lombok.Data;

@Data
public class StrategyStatistics {

    private long stocksScanned;

    private long buySignals;
    private long sellSignals;
    private long holdSignals;

    private long emaBuy;
    private long emaSell;

    private long macdBuy;
    private long macdSell;

    private long rsiBuy;
    private long rsiSell;

    private long engineeringPassed;
    private long engineeringRejected;

}