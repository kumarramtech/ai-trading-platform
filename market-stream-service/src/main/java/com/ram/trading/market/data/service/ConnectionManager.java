package com.ram.trading.market.data.service;

public interface ConnectionManager {

    void connect();

    void disconnect();

    boolean isConnected();

    void reconnect();

}