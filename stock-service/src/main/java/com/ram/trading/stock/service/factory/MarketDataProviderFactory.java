package com.ram.trading.stock.service.factory;

import com.ram.trading.stock.service.MarketDataProvider;
import com.ram.trading.stock.service.provider.UpstoxMarketDataProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class MarketDataProviderFactory {

    private final UpstoxMarketDataProvider upstoxProvider;

    public MarketDataProvider getProvider() {
        return upstoxProvider;
    }
}