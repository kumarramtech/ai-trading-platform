package com.ram.trading.market.data.service;

public interface MarketSessionService {

    boolean isMarketOpen();

    boolean isPreMarket();

    boolean isAfterMarket();
}