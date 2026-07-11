package com.ram.trading.market.data.controller;

import com.ram.trading.market.data.provider.dto.FeedAuthorizationResponse;
import com.ram.trading.market.data.provider.upstox.client.UpstoxMarketFeedClient;
import com.ram.trading.market.data.provider.upstox.websocket.UpstoxWebSocketClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/upstox")
@RequiredArgsConstructor
public class UpstoxFeedController {

    private final UpstoxWebSocketClient webSocketClient;

    private final UpstoxMarketFeedClient client;

    @GetMapping("/market-feed")
    public Mono<FeedAuthorizationResponse> authorize() {

        return client.authorizeFeed();

    }

    @GetMapping("/connect")
    public String connect() {

        webSocketClient.connect();

        return "Connecting...";

    }
}