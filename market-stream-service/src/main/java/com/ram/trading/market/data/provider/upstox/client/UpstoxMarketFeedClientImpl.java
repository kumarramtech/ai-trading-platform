package com.ram.trading.market.data.provider.upstox.client;

import com.ram.trading.market.data.auth.upstox.UpstoxProperties;
import com.ram.trading.market.data.auth.upstox.UpstoxTokenService;
import com.ram.trading.market.data.provider.dto.FeedAuthorizationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxMarketFeedClientImpl
        implements UpstoxMarketFeedClient {

    private final WebClient webClient;
    private final UpstoxProperties properties;
    private final UpstoxTokenService tokenService;

    @Override
    public Mono<FeedAuthorizationResponse> authorizeFeed() {

        return webClient.get()

                .uri(properties.getBaseUrl()
                        + "/v3/feed/market-data-feed/authorize")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + tokenService.getAccessToken())
                .header(HttpHeaders.ACCEPT, "application/json")
                .retrieve()
                .bodyToMono(FeedAuthorizationResponse.class)
                .doOnSuccess(response ->
                        log.info("Market Feed Authorized."))

                .doOnError(error ->
                        log.error("Authorization Failed", error));

    }

}