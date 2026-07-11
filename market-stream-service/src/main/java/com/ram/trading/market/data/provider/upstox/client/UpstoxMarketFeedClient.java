package com.ram.trading.market.data.provider.upstox.client;

import com.ram.trading.market.data.provider.dto.FeedAuthorizationResponse;
import reactor.core.publisher.Mono;

public interface UpstoxMarketFeedClient {

    Mono<FeedAuthorizationResponse> authorizeFeed();

}