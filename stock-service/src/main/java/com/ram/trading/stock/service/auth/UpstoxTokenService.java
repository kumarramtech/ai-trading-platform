package com.ram.trading.stock.service.auth;

import com.ram.trading.stock.client.BrokerAuthClient;
import com.ram.trading.stock.dto.UpstoxTokenResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UpstoxTokenService {

    private final BrokerAuthClient brokerAuthClient;

    public Mono<String> getAccessToken() {

        return brokerAuthClient.getAccessToken();

    }

}