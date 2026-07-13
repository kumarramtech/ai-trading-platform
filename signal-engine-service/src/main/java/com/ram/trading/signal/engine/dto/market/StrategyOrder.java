package com.ram.trading.signal.engine.dto.market;

public final class StrategyOrder {

    public static final int EMERGENCY = 1;
    public static final int STOP_LOSS = 2;
    public static final int TIME_EXIT = 3;
    public static final int TARGET = 4;
    public static final int TRAILING = 5;
    public static final int AI = 6;

    private StrategyOrder() {
    }
}