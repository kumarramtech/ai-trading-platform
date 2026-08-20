package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.watchlist.WatchlistStockResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Service
public class WatchlistClient {

    private final WebClient client;

    public WatchlistClient(
            WebClient.Builder builder,
            @Value("${watchlist.service.url}") String watchlistServiceUrl) {

        this.client = builder
                .baseUrl(watchlistServiceUrl)
                .build();
    }

    public Flux<WatchlistStockResponse> getActiveWatchlist() {

        return client
                .get()
                .uri("/api/v1/watchlist/active")
                .retrieve()
                .bodyToFlux(WatchlistStockResponse.class);
    }
}