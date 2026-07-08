package com.ram.trading.signal.engine.service.context;

import reactor.core.publisher.Mono;

public interface TradingContextService {

    Mono<TradingContext> buildTradingContext(String symbol);

}