package com.ram.trading.stock.client;


import com.ram.trading.stock.dto.LivePrice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
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

                    log.error("Unable to fetch live price for {}", symbol);

                    return Mono.error(new RuntimeException(
                            "Unable to fetch live price for " + symbol));
                })
                .bodyToMono(LivePrice.class);
    }

}