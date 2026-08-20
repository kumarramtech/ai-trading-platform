package com.ram.trading.watchlist.service.impl;

import com.ram.trading.watchlist.client.NewsAnalysisClient;
import com.ram.trading.watchlist.client.SignalEngineClient;
import com.ram.trading.watchlist.client.StockServiceClient;
import com.ram.trading.watchlist.dto.MarketTrendAnalysisResponse;
import com.ram.trading.watchlist.dto.TechnicalCandidate;
import com.ram.trading.watchlist.dto.TradableInstrumentResponse;
import com.ram.trading.watchlist.dto.WatchlistResponse;
import com.ram.trading.watchlist.entity.WatchlistStock;
import com.ram.trading.watchlist.repo.WatchlistStockRepository;
import com.ram.trading.watchlist.service.TechnicalScoringService;
import com.ram.trading.watchlist.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.ram.trading.watchlist.dto.SectorStockAnalysisResponse;
import com.ram.trading.watchlist.dto.SectorStockSuggestion;
import com.ram.trading.watchlist.dto.StockSuggestion;

import java.util.Set;
import java.util.HashSet;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistServiceImpl implements WatchlistService {

    private static final int BATCH_SIZE = 100;
    private static final double MIN_TECHNICAL_SCORE = 60.0;

    @Value("${watchlist.max-size:100}")
    private int maxWatchlistSize;

    private final StockServiceClient stockServiceClient;
    private final SignalEngineClient signalEngineClient;
    private final TechnicalScoringService technicalScoringService;
    private final WatchlistStockRepository repository;
    private final NewsAnalysisClient newsAnalysisClient;

    @Override
    public Mono<WatchlistResponse> generateWatchlist() {

        log.info("==============================================");
        log.info("WATCHLIST GENERATION STARTED");
        log.info("==============================================");

        return newsAnalysisClient
                .getMarketTrendStocks()

                .doOnNext(sectorStockAnalysis -> {

                    log.info("==============================================");
                    log.info("AI SECTOR STOCK ANALYSIS");
                    log.info("==============================================");

                    if (sectorStockAnalysis.getSectorStocks() == null
                            || sectorStockAnalysis.getSectorStocks().isEmpty()) {

                        log.info("No AI sector stock suggestions received");
                        return;
                    }

                    sectorStockAnalysis.getSectorStocks()
                            .forEach(sector -> {

                                log.info(
                                        "Sector : {}",
                                        sector.getSector()
                                );

                                if (sector.getStocks() != null) {

                                    sector.getStocks()
                                            .forEach(stock ->
                                                    log.info(
                                                            "AI Focus Stock | Symbol={} | Reason={}",
                                                            stock.getSymbol(),
                                                            stock.getReason()
                                                    )
                                            );
                                }
                            });

                    log.info("==============================================");
                })

                .onErrorResume(ex -> {

                    log.error(
                            "Unable to retrieve AI sector stock analysis. "
                                    + "Continuing with technical watchlist generation.",
                            ex
                    );

                    return Mono.just(
                            SectorStockAnalysisResponse.builder()
                                    .sectorStocks(List.of())
                                    .build()
                    );
                })

                .flatMap(sectorStockAnalysis ->

                        stockServiceClient
                                .getTradableEquities()
                                .collectList()

                                .flatMap(instruments -> {

                                    log.info(
                                            "Tradable equities received : {}",
                                            instruments.size()
                                    );

                                    if (instruments.isEmpty()) {

                                        return Mono.just(
                                                WatchlistResponse.builder()
                                                        .totalSymbols(0)
                                                        .technicalCandidates(0)
                                                        .candidates(List.of())
                                                        .build()
                                        );
                                    }

                                    /*
                                     * Build actual tradable symbol map.
                                     */
                                    Map<String, TradableInstrumentResponse>
                                            instrumentMap =
                                            instruments.stream()
                                                    .filter(instrument ->
                                                            instrument.getTradingSymbol() != null
                                                                    && !instrument
                                                                    .getTradingSymbol()
                                                                    .isBlank()
                                                    )
                                                    .collect(
                                                            java.util.stream.Collectors.toMap(
                                                                    instrument ->
                                                                            instrument
                                                                                    .getTradingSymbol()
                                                                                    .trim()
                                                                                    .toUpperCase(
                                                                                            Locale.ROOT
                                                                                    ),
                                                                    Function.identity(),
                                                                    (existing, replacement)
                                                                            -> existing
                                                            )
                                                    );

                                    List<String> symbols =
                                            instrumentMap.keySet()
                                                    .stream()
                                                    .sorted()
                                                    .toList();

                                    log.info(
                                            "Valid symbols prepared : {}",
                                            symbols.size()
                                    );

                                    /*
                                     * Extract AI suggested symbols.
                                     */
                                    Set<String> aiSuggestedSymbols =
                                            new HashSet<>();

                                    if (sectorStockAnalysis.getSectorStocks()
                                            != null) {

                                        sectorStockAnalysis.getSectorStocks()
                                                .stream()
                                                .filter(sector ->
                                                        sector.getStocks() != null
                                                )
                                                .flatMap(sector ->
                                                        sector.getStocks().stream()
                                                )
                                                .map(StockSuggestion::getSymbol)
                                                .filter(symbol ->
                                                        symbol != null
                                                                && !symbol.isBlank()
                                                )
                                                .map(symbol ->
                                                        symbol.trim()
                                                                .toUpperCase(
                                                                        Locale.ROOT
                                                                )
                                                )
                                                .forEach(
                                                        aiSuggestedSymbols::add
                                                );
                                    }

                                    log.info(
                                            "AI suggested symbols received : {}",
                                            aiSuggestedSymbols.size()
                                    );

                                    /*
                                     * Keep only symbols that exist in
                                     * our actual tradable universe.
                                     */
                                    Set<String> validAiFocusSymbols = new HashSet<>();

                                    for (String symbol : aiSuggestedSymbols) {
                                        if (instrumentMap.containsKey(symbol)) {
                                            validAiFocusSymbols.add(symbol);
                                        } else {
                                            log.warn("AI suggested symbol not found in tradable universe | Symbol={}", symbol);
                                        }
                                    }

                                    log.info("Valid AI focus symbols : {}", validAiFocusSymbols.size()
                                    );

                                    if (!validAiFocusSymbols.isEmpty()) {
                                        log.info( "Valid AI Focus Stocks : {}", validAiFocusSymbols);
                                    }

                                    /*
                                     * Scan ALL tradable stocks.
                                     *
                                     * AI focus stocks are NOT the only
                                     * stocks being scanned.
                                     */
                                    return Flux
                                            .fromIterable(symbols)
                                            .buffer(BATCH_SIZE)

                                            .concatMap(batch -> {

                                                log.info(
                                                        "Processing indicator batch | Size={}",
                                                        batch.size()
                                                );

                                                return signalEngineClient
                                                        .getTechnicalIndicators(batch)
                                                        .onErrorResume(ex -> {

                                                            log.error(
                                                                    "Indicator batch failed | "
                                                                            + "Batch Size={}",
                                                                    batch.size(),
                                                                    ex
                                                            );

                                                            return Flux.empty();
                                                        });
                                            })

                                            .map(indicator -> {

                                                String normalizedSymbol =
                                                        indicator.getSymbol()
                                                                .trim()
                                                                .toUpperCase(
                                                                        Locale.ROOT
                                                                );

                                                TradableInstrumentResponse
                                                        instrument =
                                                        instrumentMap.get(
                                                                normalizedSymbol
                                                        );

                                                String companyName =
                                                        instrument != null
                                                                ? instrument.getCompanyName()
                                                                : null;

                                                return technicalScoringService.score(
                                                        indicator,
                                                        companyName
                                                );
                                            })

                                            /*
                                             * Keep existing technical rule.
                                             */
                                            .filter(candidate ->
                                                    candidate.getTechnicalScore()
                                                            >= MIN_TECHNICAL_SCORE
                                            )

                                            /*
                                             * AI priority comes first.
                                             *
                                             * Within AI focus stocks,
                                             * sort by technical score.
                                             *
                                             * Then remaining technically
                                             * qualified stocks are sorted
                                             * by technical score.
                                             *
                                             * Technical score itself
                                             * is NEVER modified.
                                             */
                                            .sort(
                                                    Comparator
                                                            .comparing(
                                                                    (TechnicalCandidate candidate) ->
                                                                            validAiFocusSymbols.contains(
                                                                                    candidate
                                                                                            .getSymbol()
                                                                                            .trim()
                                                                                            .toUpperCase(
                                                                                                    Locale.ROOT
                                                                                            )
                                                                            )
                                                            )
                                                            .reversed()
                                                            .thenComparing(
                                                                    TechnicalCandidate
                                                                            ::getTechnicalScore,
                                                                    Comparator.reverseOrder()
                                                            )
                                            )

                                            .take(maxWatchlistSize)

                                            .collectList()

                                            .map(candidates -> {

                                                List<String> symbolsToSave =
                                                        candidates.stream()
                                                                .map(
                                                                        TechnicalCandidate
                                                                                ::getSymbol
                                                                )
                                                                .toList();

                                                replaceWatchlist(symbolsToSave);

                                                long aiFocusCandidates =
                                                        candidates.stream()
                                                                .filter(candidate ->
                                                                        validAiFocusSymbols.contains(
                                                                                candidate
                                                                                        .getSymbol()
                                                                                        .trim()
                                                                                        .toUpperCase(
                                                                                                Locale.ROOT
                                                                                        )
                                                                        )
                                                                )
                                                                .count();

                                                log.info(
                                                        "Final watchlist candidates : {}",
                                                        candidates.size()
                                                );

                                                log.info(
                                                        "AI focus stocks in watchlist : {}",
                                                        aiFocusCandidates
                                                );

                                                return WatchlistResponse.builder()
                                                        .totalSymbols(
                                                                symbols.size()
                                                        )
                                                        .technicalCandidates(
                                                                candidates.size()
                                                        )
                                                        .candidates(
                                                                candidates
                                                        )
                                                        .build();
                                            });
                                })
                )

                .doOnSuccess(response -> {

                    log.info("==============================================");
                    log.info("WATCHLIST GENERATION COMPLETED");

                    if (response != null) {

                        log.info(
                                "Total Symbols        : {}",
                                response.getTotalSymbols()
                        );

                        log.info(
                                "Technical Candidates : {}",
                                response.getTechnicalCandidates()
                        );
                    }

                    log.info("==============================================");
                });
    }

    public WatchlistStock addStock(String symbol) {

        if (repository.existsBySymbol(symbol)) {
            throw new RuntimeException(
                    "Stock already exists");
        }

        WatchlistStock stock =
                WatchlistStock.builder()
                        .symbol(symbol.toUpperCase())
                        .active(true)
                        .createdAt(LocalDateTime.now())
                        .build();

        return repository.save(stock);
    }

    public List<WatchlistStock> getAllStocks() {

        return repository.findByActiveTrue();
    }

    public void removeStock(String symbol) {

        repository.deleteBySymbol(
                symbol.toUpperCase());
    }

    @Override
    public void replaceWatchlist(List<String> symbols) {

        repository.deleteAll();

        List<WatchlistStock> stocks = symbols.stream()
                .map(symbol ->
                        WatchlistStock.builder()
                                .symbol(symbol.toUpperCase())
                                .active(true)
                                .createdAt(LocalDateTime.now())
                                .build())
                .toList();

        repository.saveAll(stocks);
    }
}