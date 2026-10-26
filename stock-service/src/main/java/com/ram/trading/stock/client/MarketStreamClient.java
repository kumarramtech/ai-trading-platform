package com.ram.trading.stock.client;


import com.ram.trading.stock.dto.LivePrice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketStreamClient {

    private final WebClient webClient;

    @Value("${market.stream.base-url}")
    private String marketStreamBaseUrl;

    public Mono<LivePrice> getLivePrice(String symbol) {

        log.info("Fetching live price from Market Stream Service : {}", symbol);

        return webClient.get()
                .uri(marketStreamBaseUrl + "/market-stream/live-price/{symbol}", symbol)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> {

                    /*
                     * Market Stream answers 404 when it has no tick for the
                     * symbol (not subscribed / not traded yet). That is an
                     * expected state, so surface it as 404 instead of a 500
                     * with a stack trace in every caller's log.
                     */
                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {

                        log.warn("Live price not available for {}", symbol);

                        return Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Live price not available for " + symbol));
                    }

                    log.error("Unable to fetch live price for {} | Status={}",
                            symbol, response.statusCode().value());

                    return Mono.error(new RuntimeException(
                            "Unable to fetch live price for " + symbol));
                })
                .bodyToMono(LivePrice.class);
    }

}