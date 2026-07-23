package com.ram.trading.signal.engine.service.interfac;

public interface MarketSessionService {

    boolean isMarketOpen();

    boolean isPreMarket();

    boolean isAfterMarket();
}