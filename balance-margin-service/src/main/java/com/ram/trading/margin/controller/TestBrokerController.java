package com.ram.trading.margin.controller;

import com.ram.trading.margin.client.BrokerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestBrokerController {

    private final BrokerClient brokerClient;

    @GetMapping("/broker-token")
    public Mono<String> getBrokerToken() {
        return brokerClient.getAccessToken();
    }
}