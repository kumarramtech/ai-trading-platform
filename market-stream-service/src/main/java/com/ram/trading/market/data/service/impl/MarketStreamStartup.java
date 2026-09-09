package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.service.MarketStreamService;
import com.ram.trading.market.data.service.MarketSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MarketStreamStartup {

    private final MarketStreamService marketStreamService;

    private final MarketSessionService marketSessionService;

    @Value("${market.stream.auto-start:false}")
    private boolean autoStart;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {

        if (autoStart && marketSessionService.isMarketOpen()) {
            marketStreamService.start();
        } else if (autoStart) {
            // Connection will be started by MarketConnectionScheduler when the market opens.
        }

    }

}