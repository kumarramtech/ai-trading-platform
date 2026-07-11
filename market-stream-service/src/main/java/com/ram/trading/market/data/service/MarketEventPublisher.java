package com.ram.trading.market.data.service;

import com.ram.trading.market.data.dto.LivePrice;
import com.ram.trading.market.data.dto.Tick;

public interface MarketEventPublisher {

    void publishPriceUpdate(LivePrice livePrice);

    void publishTick(Tick tick);

}