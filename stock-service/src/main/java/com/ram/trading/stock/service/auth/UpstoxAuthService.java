package com.ram.trading.stock.service.auth;

import com.ram.trading.stock.client.UpstoxClient;
import com.ram.trading.stock.client.properties.UpstoxProperties;
import com.ram.trading.stock.dto.UpstoxTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
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

        return upstoxClient.exchangeCode(code)
                .map(token -> {
                    tokenService.saveToken(token);
                    return "Authentication Successful";
                });
    }
}