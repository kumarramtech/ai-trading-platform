package com.ram.trading.watchlist.client;

import com.ram.trading.watchlist.dto.WatchlistMarketQuoteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpstoxMarketClient {

    private final WebClient.Builder webClientBuilder;
    private final BrokerAuthClient brokerAuthClient;

    @Value("${upstox.market.base-url}")
    private String upstoxMarketBaseUrl;

    /**
     * Fetch market quote for a single instrument.
     */
    public Mono<WatchlistMarketQuoteResponse> getQuote(
            String instrumentKey) {

        if (instrumentKey == null || instrumentKey.isBlank()) {

            return Mono.error(
                    new IllegalArgumentException(
                            "Instrument key must not be null or blank"));
        }

        return getQuotes(List.of(instrumentKey));
    }

    /**
     * Fetch full market quotes for multiple instruments.
     *
     * Upstox supports up to 500 instrument keys in a single
     * Full Market Quotes request.
     */
    public Mono<WatchlistMarketQuoteResponse> getQuotes(
            List<String> instrumentKeys) {

        if (instrumentKeys == null || instrumentKeys.isEmpty()) {

            return Mono.error(
                    new IllegalArgumentException(
                            "Instrument keys must not be null or empty"));
        }

        List<String> validInstrumentKeys =
                instrumentKeys.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(key -> !key.isBlank())
                        .distinct()
                        .toList();

        if (validInstrumentKeys.isEmpty()) {

            return Mono.error(
                    new IllegalArgumentException(
                            "No valid instrument keys supplied"));
        }

        if (validInstrumentKeys.size() > 500) {

            return Mono.error(
                    new IllegalArgumentException(
                            "Maximum 500 instruments allowed per Upstox quote request"));
        }

        String instrumentKeyQuery =
                validInstrumentKeys.stream()
                        .collect(Collectors.joining(","));

        log.info(
                "Fetching Upstox full market quotes | InstrumentCount={}",
                validInstrumentKeys.size());

        return brokerAuthClient
                .getAccessToken()
                .flatMap(token ->
                        webClientBuilder
                                .baseUrl(upstoxMarketBaseUrl)
                                .codecs(configurer ->
                                        configurer.defaultCodecs()
                                                .maxInMemorySize(
                                                        2 * 1024 * 1024))
                                .build()
                                .get()
                                .uri(uriBuilder ->
                                        uriBuilder
                                                .path("/v2/market-quote/quotes")
                                                .queryParam(
                                                        "instrument_key",
                                                        instrumentKeyQuery)
                                                .build())
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + token)
                                .accept(MediaType.APPLICATION_JSON)
                                .retrieve()
                                .bodyToMono(
                                        WatchlistMarketQuoteResponse.class)
                );
    }
}