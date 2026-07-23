package com.ram.trading.auth.service.controller;

import com.ram.trading.auth.service.constant.BrokerConstants;
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
            @RequestParam String code) {
        log.info("Authorization Code={}", code);
        return authService.authenticate(code);
    }

    @Operation(
            summary = "Get Access Token",
            description = "Returns the currently stored Upstox access token."
    )
    @GetMapping("/auth/token")
    public String getAccessToken() {
        return brokerSessionService.getAccessToken(BrokerConstants.UPSTOX);
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