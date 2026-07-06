package com.ram.trading.stock.client;

import com.ram.trading.stock.client.properties.UpstoxProperties;
import com.ram.trading.stock.dto.MarketQuoteResponse;
import com.ram.trading.stock.service.auth.UpstoxTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxMarketClient {

    private final WebClient webClient;
    private final UpstoxProperties properties;
    private final UpstoxTokenService tokenService;

    public Mono<MarketQuoteResponse> getQuote(String instrumentKey) {

        log.info("Fetching live quote for {}", instrumentKey);

        String token = tokenService.getAccessToken();

        log.info("Using Access Token : {}", token.substring(0,10));
        return webClient.get()

                .uri(properties.getMarketUrl()
                        + "/market-quote/quotes?instrument_key="
                        + instrumentKey)

                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + tokenService.getAccessToken())

                .accept(MediaType.APPLICATION_JSON)

                .retrieve()

                .onStatus(HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .flatMap(error -> {

                                    log.error("Upstox Quote API Error: {}", error);

                                    return Mono.error(new RuntimeException(error));
                                }))

                .bodyToMono(MarketQuoteResponse.class)

                .doOnSuccess(response ->
                        log.info("Successfully received market quote from Upstox."))

                .doOnError(error ->
                        log.error("Error while fetching quote", error));
    }
}