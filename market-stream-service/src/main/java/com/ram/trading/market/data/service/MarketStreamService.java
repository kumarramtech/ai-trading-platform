package com.ram.trading.market.data.service;

import com.ram.trading.market.data.dto.HealthResponse;
import com.ram.trading.market.data.dto.MarketInstrument;
import com.ram.trading.market.data.dto.MarketStreamStatus;

import java.util.List;

public interface MarketStreamService {

    void start();

    void stop();

    MarketStreamStatus getStatus();

    List<MarketInstrument> getInstruments();

    HealthResponse getHealth();

}