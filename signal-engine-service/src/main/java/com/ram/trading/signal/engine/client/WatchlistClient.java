package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.watchlist.MiddayMarketSnapshotResponse;
import com.ram.trading.signal.engine.dto.watchlist.WatchlistStockResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
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

    public Mono<MiddayMarketSnapshotResponse> getMiddayMarketSnapshot() {

        return client
                .get()
                .uri("/api/v1/watchlist/midday")
                .retrieve()
                .bodyToMono(MiddayMarketSnapshotResponse.class)

                .doOnSuccess(snapshot -> {

                    if (snapshot == null) {
                        log.warn(
                                "MIDDAY MARKET SNAPSHOT | " +
                                        "Watchlist Service returned empty response");
                        return;
                    }

                    log.info(
                            "MIDDAY MARKET SNAPSHOT RECEIVED | " +
                                    "Gainers={} | Losers={} | Trending={} | " +
                                    "TotalCandidates={}",
                            snapshot.getTopGainers() == null
                                    ? 0
                                    : snapshot.getTopGainers().size(),
                            snapshot.getTopLosers() == null
                                    ? 0
                                    : snapshot.getTopLosers().size(),
                            snapshot.getTrendingStocks() == null
                                    ? 0
                                    : snapshot.getTrendingStocks().size(),
                            snapshot.getTotalCandidates());
                })

                .doOnError(error ->
                        log.error(
                                "Failed to retrieve midday market snapshot " +
                                        "from Watchlist Service",
                                error));
    }
}