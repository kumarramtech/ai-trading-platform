package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.service.MarketStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MarketStreamStartup {

    private final MarketStreamService marketStreamService;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {

        marketStreamService.start();

    }

}