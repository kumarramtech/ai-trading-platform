package com.ram.trading.stock.client;

import com.ram.trading.stock.client.properties.UpstoxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpstoxHistoricalCandleClient {

    private final WebClient webClient;
    private final UpstoxProperties properties;

    public Mono<String> getHistoricalCandles(
            String accessToken,
            String instrumentKey,
            String interval,
            String fromDate,
            String toDate) {

        log.info("""
                Historical Candle API

                Instrument : {}
                Interval   : {}
                From       : {}
                To         : {}
                """,
                instrumentKey,
                interval,
                fromDate,
                toDate);


        String url = UriComponentsBuilder
                .fromHttpUrl(properties.getBaseUrl())
                .path("/v3/historical-candle/{instrumentKey}/{interval}/{toDate}/{fromDate}")
                .buildAndExpand(
                        instrumentKey,
                        interval,
                        toDate,
                        fromDate)
                .toUriString();
        log.info("Calling Upstox Historical Candle API...");
        log.info("URL : {}", url);
        return webClient
                .get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(String.class);

    }

}