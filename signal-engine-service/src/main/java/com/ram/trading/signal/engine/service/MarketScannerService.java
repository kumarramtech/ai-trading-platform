package com.ram.trading.signal.engine.service;


import com.ram.trading.signal.engine.entity.WatchlistStock;
import com.ram.trading.signal.engine.repo.WatchlistStockRepository;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.config.MarketSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketScannerService {

    private final WatchlistStockRepository watchlistRepository;

    private final MarketSessionService marketSessionService;

    private final SignalGenerationService signalGenerationService;

    @Value("${trading.ai.max-symbols-per-scan:10}")
    private int maxSymbolsPerScan;

    public void scanMarket() {

        if (!marketSessionService.isTradingAllowed()) {
            log.info("Market is closed. Skipping market scan.");
            return;
        }

        List<WatchlistStock> stocks = watchlistRepository.findByActiveTrue();

        log.info("==================================================");
        log.info("Total Watchlist Stocks : {}", stocks.size());
        log.info("AI Scan Limit          : {}", maxSymbolsPerScan);
        log.info("==================================================");

        stocks.stream()
                .limit(maxSymbolsPerScan)
                .forEach(stock -> scanSymbol(stock.getSymbol()));
    }

    private void scanSymbol(String symbol) {

        log.info("=======================================");
        log.info("Scanning Symbol : {}", symbol);
        log.info("=======================================");

        signalGenerationService
                .generateSignal(symbol)
                .doOnSuccess(signal -> {

                    if (signal != null) {
                        log.info("Signal Generated : {} {}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                })
                .doOnError(error ->
                        log.error("Market Scan Failed : {}",
                                symbol,
                                error))
                .subscribe();
    }

}