package com.ram.trading.stock.client;

import reactor.core.publisher.Mono;

public interface BrokerAuthClient {

    Mono<String> getAccessToken();

}