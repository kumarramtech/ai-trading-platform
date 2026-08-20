package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.WatchlistClient;
import com.ram.trading.signal.engine.config.MarketSessionService;
import com.ram.trading.signal.engine.dto.watchlist.WatchlistStockResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketScannerService {

    private final WatchlistClient watchlistClient;

    private final MarketSessionService marketSessionService;

    private final SignalGenerationService signalGenerationService;

    @Value("${trading.ai.max-symbols-per-scan:10}")
    private int maxSymbolsPerScan;

    /**
     * Points to the next stock index to be scanned.
     */
    private int currentIndex = 0;

    public synchronized void scanMarket() {

        if (!marketSessionService.isTradingAllowed()) {
            log.info("Market is closed. Skipping market scan.");
            return;
        }

        watchlistClient
                .getActiveWatchlist()
                .collectList()

                .doOnSuccess(this::processWatchlist)

                .doOnError(error ->
                        log.error(
                                "Failed to retrieve active watchlist from Watchlist Service. "
                                        + "Skipping current market scan.",
                                error
                        )
                )

                .subscribe();
    }

    private void processWatchlist(
            List<WatchlistStockResponse> stocks) {

        if (stocks == null || stocks.isEmpty()) {

            log.warn(
                    "No active watchlist stocks received from Watchlist Service."
            );

            return;
        }

        int totalStocks = stocks.size();

        log.info("========================================");
        log.info("MARKET SCAN STARTED");
        log.info("Total Watchlist Stocks : {}", totalStocks);
        log.info("AI Scan Limit          : {}", maxSymbolsPerScan);

        /*
         * If the watchlist changed and the current index is
         * outside the available range, restart from beginning.
         */
        if (currentIndex >= totalStocks) {
            currentIndex = 0;
        }

        int startIndex = currentIndex;

        int endIndex = Math.min(
                startIndex + maxSymbolsPerScan,
                totalStocks
        );

        log.info(
                "Scanning Batch : startIndex={}, endIndex={}, totalStocks={}",
                startIndex,
                endIndex - 1,
                totalStocks
        );

        List<WatchlistStockResponse> batch =
                stocks.subList(startIndex, endIndex);

        batch.forEach(stock ->
                scanSymbol(stock.getSymbol())
        );

        /*
         * Move the cursor to the next batch.
         * If the last batch has been scanned,
         * restart from the beginning.
         */
        currentIndex = endIndex;

        if (currentIndex >= totalStocks) {

            log.info(
                    "Completed full watchlist scan. Resetting cursor to beginning."
            );

            currentIndex = 0;
        }

        log.info(
                "Market Scan Completed | Next Scan Start Index : {}",
                currentIndex
        );

        log.info("========================================");
    }

    private void scanSymbol(String symbol) {

        log.info("Scanning Symbol : {}", symbol);

        signalGenerationService
                .generateSignal(symbol)
                .doOnSuccess(signal -> {

                    if (signal != null) {

                        log.info(
                                "Signal Generated : {} {}",
                                signal.getSymbol(),
                                signal.getSignal()
                        );
                    }
                })
                .doOnError(error ->
                        log.error(
                                "Market Scan Failed : {}",
                                symbol,
                                error
                        )
                )
                .subscribe();
    }
}