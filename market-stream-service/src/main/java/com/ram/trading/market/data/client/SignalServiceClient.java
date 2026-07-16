package com.ram.trading.market.data.client;


import com.ram.trading.market.data.dto.Tick;

public interface SignalServiceClient {

    void publishTick(Tick tick);

}