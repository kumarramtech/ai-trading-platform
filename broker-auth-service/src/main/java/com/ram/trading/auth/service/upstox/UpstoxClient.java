package com.ram.trading.auth.service.upstox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxClient {

    private final WebClient webClient;

    private final UpstoxProperties properties;

    public Mono<UpstoxTokenResponse> getAccessToken(String code) {

        return webClient.post()
                .uri(properties.getTokenUrl())
                .body(BodyInserters
                        .fromFormData("code", code)
                        .with("client_id", properties.getClientId())
                        .with("client_secret", properties.getClientSecret())
                        .with("redirect_uri", properties.getRedirectUri())
                        .with("grant_type", "authorization_code"))
                .retrieve()
                .bodyToMono(UpstoxTokenResponse.class);
    }

    public Mono<UpstoxTokenResponse> exchangeCode(String code) {

        return webClient.post()
                .uri(properties.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("code", code)
                                .with("client_id", properties.getClientId())
                                .with("client_secret", properties.getClientSecret())
                                .with("redirect_uri", properties.getRedirectUri())
                                .with("grant_type", "authorization_code"))
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .flatMap(error -> {
                                    log.error("Upstox Error={}", error);
                                    return Mono.error(new RuntimeException(error));
                                }))
                .bodyToMono(UpstoxTokenResponse.class)
                .doOnSuccess(token -> log.info("Access Token Received."));
    }

}