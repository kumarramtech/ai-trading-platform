package com.ram.trading.auth.service.service;

import com.ram.trading.auth.service.dto.AuthenticationStatusResponse;
import com.ram.trading.auth.service.dto.TokenResponse;
import reactor.core.publisher.Mono;

public interface BrokerAuthService {

    Mono<Void> login();

    Mono<Void> callback(String code);

    TokenResponse getToken();

    AuthenticationStatusResponse getStatus();

}