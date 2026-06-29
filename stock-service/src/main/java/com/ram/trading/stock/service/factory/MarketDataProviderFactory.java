package com.ram.trading.stock.service.factory;

import com.ram.trading.stock.service.MarketDataProvider;
import com.ram.trading.stock.service.provider.MockMarketDataProvider;
import com.ram.trading.stock.service.provider.UpstoxMarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketDataProviderFactory {

    private final MockMarketDataProvider mockProvider;

    private final UpstoxMarketDataProvider upstoxProvider;

    @Value("${market.provider}")
    private String provider;

    public MarketDataProvider getProvider() {

        log.info("Using Market Provider: {}", provider);
        if ("upstox".equalsIgnoreCase(provider)) {
            return upstoxProvider;
        }

        return mockProvider;
    }

}