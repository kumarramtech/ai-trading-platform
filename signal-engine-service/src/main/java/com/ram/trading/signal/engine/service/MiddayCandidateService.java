package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.WatchlistClient;
import com.ram.trading.signal.engine.dto.watchlist.MiddayMarketSnapshotResponse;
import com.ram.trading.signal.engine.dto.watchlist.TrendingStockResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MiddayCandidateService {

    private final WatchlistClient watchlistClient;

    private final MiddayCandidateStore middayCandidateStore;

    /**
     * Retrieves the midday market discovery snapshot and builds
     * a unique candidate pool from:
     *
     * - Top Gainers
     * - Top Losers
     * - Trending Stocks
     *
     * IMPORTANT:
     * This method ONLY discovers candidates.
     *
     * It does NOT:
     * - generate BUY/SELL signals
     * - call AI
     * - call Risk Guard
     * - create paper trades
     */
    public Mono<List<TrendingStockResponse>> discoverCandidates() {

        log.info("======================================================");
        log.info("MIDDAY CANDIDATE DISCOVERY STARTED");
        log.info("======================================================");

        return watchlistClient
                .getMiddayMarketSnapshot()

                .map(this::buildUniqueCandidatePool)

                .doOnNext(candidates -> {

                    middayCandidateStore.store(candidates);

                    log.info(
                            "MIDDAY CANDIDATES STORED | " +
                                    "UniqueCandidates={}",
                            candidates.size()
                    );
                })

                .doOnSuccess(candidates -> {

                    if (candidates == null) {
                        log.warn(
                                "MIDDAY CANDIDATE DISCOVERY | " +
                                        "No candidate pool returned"
                        );
                        return;
                    }

                    log.info(
                            "MIDDAY CANDIDATE DISCOVERY COMPLETED | " +
                                    "UniqueCandidates={}",
                            candidates.size()
                    );

                    candidates.forEach(candidate ->
                            log.info(
                                    "MIDDAY CANDIDATE | " +
                                            "Symbol={} | Price={} | Change={} | " +
                                            "Score={} | Direction={} | Level={}",
                                    candidate.getTradingSymbol(),
                                    candidate.getLastPrice(),
                                    candidate.getChangePercentage(),
                                    candidate.getTrendingScore(),
                                    candidate.getTrendDirection(),
                                    candidate.getTrendingLevel()
                            )
                    );
                })

                .doOnError(error ->
                        log.error(
                                "MIDDAY CANDIDATE DISCOVERY FAILED",
                                error
                        ));
    }

    /**
     * Builds one unique candidate pool from all three
     * discovery categories.
     *
     * LinkedHashMap is intentionally used so:
     * - duplicates are removed
     * - insertion order is preserved
     */
    private List<TrendingStockResponse> buildUniqueCandidatePool(
            MiddayMarketSnapshotResponse snapshot) {

        if (snapshot == null) {
            return List.of();
        }

        Map<String, TrendingStockResponse> uniqueCandidates =
                new LinkedHashMap<>();

        addCandidates(
                uniqueCandidates,
                snapshot.getTopGainers());

        addCandidates(
                uniqueCandidates,
                snapshot.getTopLosers());

        addCandidates(
                uniqueCandidates,
                snapshot.getTrendingStocks());

        return new ArrayList<>(uniqueCandidates.values());
    }

    private void addCandidates(
            Map<String, TrendingStockResponse> uniqueCandidates,
            List<TrendingStockResponse> candidates) {

        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        for (TrendingStockResponse candidate : candidates) {

            if (candidate == null) {
                continue;
            }

            String symbol = candidate.getTradingSymbol();

            if (symbol == null || symbol.isBlank()) {
                continue;
            }

            uniqueCandidates.putIfAbsent(
                    symbol.trim().toUpperCase(),
                    candidate);
        }
    }
}