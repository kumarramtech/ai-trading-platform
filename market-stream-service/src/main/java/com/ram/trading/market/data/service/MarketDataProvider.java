package com.ram.trading.market.data.service;

import java.util.List;

public interface MarketDataProvider {
    void connect();
    void disconnect();
    void subscribe(List<String> instruments);
    void unsubscribe(List<String> instruments);
}