package com.ram.trading.signal.engine.config;

public interface MarketSessionService {

    boolean isMarketOpen();

    boolean isTradingAllowed();
}