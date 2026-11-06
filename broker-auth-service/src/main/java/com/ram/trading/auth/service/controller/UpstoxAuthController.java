package com.ram.trading.auth.service.controller;

import com.ram.trading.auth.service.constant.BrokerConstants;
import com.ram.trading.auth.service.notification.BrokerAuthNotifier;
import com.ram.trading.auth.service.notification.UpstoxLoginWatch;
import com.ram.trading.auth.service.service.BrokerSessionService;
import com.ram.trading.auth.service.upstox.UpstoxAuthService;
import com.ram.trading.auth.service.upstox.UpstoxTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;

@Tag(
        name = "Broker Authentication",
        description = "APIs for Upstox authentication and session management"
)
@RestController
@RequestMapping("/upstox")
@RequiredArgsConstructor
@Slf4j
public class UpstoxAuthController {

    private final UpstoxAuthService authService;
    private final UpstoxTokenService tokenService;
    private final BrokerSessionService brokerSessionService;
    private final BrokerAuthNotifier notifier;
    private final UpstoxLoginWatch loginWatch;

    @Operation(
            summary = "Login to Upstox",
            description = "Redirects the user to the Upstox OAuth login page."
    )
    @GetMapping("/login")
    public Mono<ResponseEntity<Void>> login() {
        return Mono.just(
                ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(authService.getAuthorizationUrl()))
                        .build());
    }

    @Operation(
            summary = "OAuth Callback",
            description = "Processes the authorization code returned by Upstox and stores the access token."
    )
    @GetMapping("/callback")
    public Mono<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) {
        if (code == null || code.isBlank()) {
            // Upstox sends the user back without a code when the login is refused or cancelled.
            String reason = error == null ? "Upstox sent no authorization code."
                    : "Upstox refused the login: " + error + (errorDescription == null ? "" : " - " + errorDescription);
            log.warn("Upstox callback without a code: {}", reason);
            notifier.loginFailed(reason);
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, reason));
        }
        log.info("Upstox authorization code received");
        return authService.authenticate(code);
    }

    @Operation(
            summary = "Get Access Token",
            description = "Returns the currently stored Upstox access token."
    )
    @GetMapping("/auth/token")
    public String getAccessToken() {
        try {
            return brokerSessionService.getAccessToken(BrokerConstants.UPSTOX);
        } catch (RuntimeException ex) {
            loginWatch.tokenRequestFailed(ex);
            throw ex;
        }
    }

    @Operation(
            summary = "Broker Connection Status",
            description = "Checks whether the broker session is currently authenticated."
    )
    @GetMapping("/status")
    public String status() {
        return tokenService.isAuthenticated() ? "CONNECTED" : "NOT CONNECTED";
    }
}