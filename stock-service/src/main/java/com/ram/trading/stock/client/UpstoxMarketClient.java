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

        return tokenService.getAccessToken()

                .flatMap(token ->

                        webClient.get()

                                .uri(properties.getMarketUrl()
                                        + "/market-quote/quotes?instrument_key="
                                        + instrumentKey)

                                .header(HttpHeaders.AUTHORIZATION,
                                        "Bearer " + token)

                                .accept(MediaType.APPLICATION_JSON)

                                .retrieve()

                                .bodyToMono(MarketQuoteResponse.class)

                );
    }
}