package com.ram.trading.auth.service.upstox;


import com.ram.trading.auth.service.constant.BrokerConstants;
import com.ram.trading.auth.service.notification.BrokerAuthNotifier;
import com.ram.trading.auth.service.service.BrokerSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpstoxAuthService {

    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_REASON = 300;

    private final UpstoxProperties properties;
    private final UpstoxClient upstoxClient;
    private final UpstoxTokenService tokenService;
    private final BrokerSessionService brokerSessionService;
    private final BrokerAuthNotifier notifier;

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
                .timeout(EXCHANGE_TIMEOUT)
                .switchIfEmpty(Mono.error(new IllegalStateException("Upstox returned no token")))
                .map(token -> {
                    log.info("Received Token from Upstox");
                    if (token.getAccessToken() == null || token.getAccessToken().isBlank()) {
                        throw new IllegalStateException("Upstox returned no token");
                    }
                    tokenService.saveToken(token);
                    notifier.loginSucceeded(expiry());
                    return "Authentication Successful";
                })
                .doOnError(ex -> {
                    log.error("Upstox authentication failed: {}", ex.toString());
                    notifier.loginFailed(reason(ex, code));
                });

    }

    /** The login is already saved: a problem reading the expiry back must not fail it. */
    private LocalDateTime expiry() {
        try {
            return brokerSessionService.getBrokerSession(BrokerConstants.UPSTOX).getExpiresAt();
        } catch (RuntimeException ex) {
            log.warn("Could not read the Upstox token expiry: {}", ex.toString());
            return null;
        }
    }

    /** Short, and without the one-time code, which Upstox's error text could repeat. */
    static String reason(Throwable ex, String code) {
        String text = ex instanceof TimeoutException ? "Upstox did not answer in time."
                : ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (code != null && !code.isBlank()) {
            text = text.replace(code, "***");
        }
        return text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON) + "...";
    }
}