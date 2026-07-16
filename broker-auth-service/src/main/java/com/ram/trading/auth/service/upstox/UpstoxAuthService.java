package com.ram.trading.auth.service.upstox;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpstoxAuthService {

    private final UpstoxProperties properties;
    private final UpstoxClient upstoxClient;
    private final UpstoxTokenService tokenService;

    public String getAuthorizationUrl() {

        return properties.getAuthorizationUrl()
                + "?response_type=code"
                + "&client_id=" + properties.getClientId()
                + "&redirect_uri="
                + URLEncoder.encode(
                properties.getRedirectUri(),
                StandardCharsets.UTF_8);
    }

    public Mono<UpstoxTokenResponse> exchangeCode(String code) {
        return upstoxClient.getAccessToken(code);
    }

    public Mono<String> authenticate(String code) {

        log.info("Authentication started...");

        return upstoxClient.exchangeCode(code)
                .map(token -> {
                    log.info("Received Token from Upstox");
                    tokenService.saveToken(token);
                    return "Authentication Successful";
                });

    }
}