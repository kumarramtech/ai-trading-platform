package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.dto.LivePrice;
import com.ram.trading.market.data.dto.Tick;
import com.ram.trading.market.data.service.MarketEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MarketEventPublisherImpl implements MarketEventPublisher {

    @Override
    public void publishPriceUpdate(LivePrice livePrice) {

        log.debug("Publishing LivePrice : {}", livePrice);

    }

    @Override
    public void process(Tick tick) {

        log.debug("Publishing Tick : {}", tick);

    }
}