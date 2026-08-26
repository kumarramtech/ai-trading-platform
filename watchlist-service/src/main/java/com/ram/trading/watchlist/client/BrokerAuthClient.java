package com.ram.trading.watchlist.client;

import reactor.core.publisher.Mono;

public interface BrokerAuthClient {

    Mono<String> getAccessToken();

}