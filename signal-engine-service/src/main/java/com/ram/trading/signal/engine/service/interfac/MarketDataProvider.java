package com.ram.trading.signal.engine.service.interfac;

import com.ram.trading.signal.engine.dto.StockResponse;
import reactor.core.publisher.Mono;

public interface MarketDataProvider {

    Mono<StockResponse> getStockPrice(
            String symbol);
}