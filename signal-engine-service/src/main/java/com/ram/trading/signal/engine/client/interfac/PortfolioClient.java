package com.ram.trading.signal.engine.client.interfac;

import reactor.core.publisher.Mono;

public interface PortfolioClient {

    Mono<Void> openPosition(
            String symbol,
            Integer quantity,
            Double entryPrice);

    Mono<Void> closePosition(
            String symbol,
            Integer quantity);
}