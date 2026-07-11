package com.ram.trading.market.data.provider.upstox.websocket;

import java.util.List;

public interface UpstoxWebSocketClient {

    void connect();

    void disconnect();

    void subscribe(List<String> instrumentKeys);

    boolean isConnected();

}