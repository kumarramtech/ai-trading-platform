package com.ram.trading.watchlist.service;

import com.ram.trading.watchlist.client.StockServiceClient;
import com.ram.trading.watchlist.client.UpstoxMarketClient;
import com.ram.trading.watchlist.dto.TradableInstrumentResponse;
import com.ram.trading.watchlist.dto.TrendingMarketSnapshot;
import com.ram.trading.watchlist.dto.TrendingStock;
import com.ram.trading.watchlist.dto.WatchlistMarketQuoteResponse;
import com.ram.trading.watchlist.dto.WatchlistOhlc;
import com.ram.trading.watchlist.dto.WatchlistQuoteData;
import com.ram.trading.watchlist.dto.midday.MiddayMarketSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrendingStockService {

    private static final int MAX_INSTRUMENTS_PER_REQUEST = 500;

    private static final int DEFAULT_TOP_RESULTS = 20;

    private final StockServiceClient stockServiceClient;

    private final UpstoxMarketClient upstoxMarketClient;

    private final TrendingScoreCalculator trendingScoreCalculator;

    /**
     * Previous market snapshot for each instrument.
     *
     * Key   : Upstox instrument key
     * Value : Previous snapshot
     *
     * V1 is intentionally in-memory.
     */
    private final Map<String, TrendingMarketSnapshot> previousSnapshots =
            new HashMap<>();

    /**
     * Returns the default top trending stocks.
     */
    public Mono<List<TrendingStock>> getTrendingStocks() {

        return getTrendingStocks(DEFAULT_TOP_RESULTS);
    }

    /**
     * Returns the top N trending stocks.
     *
     * Observation-only.
     *
     * This method does NOT:
     *
     * - generate BUY/SELL signals
     * - call AI
     * - call Risk Guard
     * - create paper trades
     */
    public Mono<List<TrendingStock>> getTrendingStocks(int topN) {

        int requestedTopN =
                topN > 0
                        ? topN
                        : DEFAULT_TOP_RESULTS;

        log.info(
                "Starting Trending Stock Scan | RequestedTopN={}",
                requestedTopN);

        return stockServiceClient
                .getTradableEquities()
                .collectList()
                .flatMap(instruments -> {

                    if (instruments.isEmpty()) {

                        log.warn(
                                "Trending Stock Scan | No tradable instruments found");

                        return Mono.just(List.of());
                    }

                    log.info(
                            "Trending Stock Scan | Tradable Instruments={}",
                            instruments.size());

                    return scanInBatches(
                            instruments,
                            requestedTopN);
                });
    }

    /**
     * Splits the complete instrument universe into
     * Upstox-compatible batches.
     */

    /**
     * Scans the complete instrument universe.
     *
     * IMPORTANT:
     * This method does NOT rank or limit the results.
     *
     * It is the common market-scan foundation used by:
     *
     * - Existing Trending Stock API
     * - Midday Market Discovery
     */
    private Mono<List<TrendingStock>> scanAllInBatches(
            List<TradableInstrumentResponse> instruments) {

        if (instruments == null || instruments.isEmpty()) {

            return Mono.just(List.of());
        }

        List<List<TradableInstrumentResponse>> batches =
                partition(
                        instruments,
                        MAX_INSTRUMENTS_PER_REQUEST);

        log.info(
                "Trending Stock Scan | " +
                        "BatchCount={} | BatchSize={} | " +
                        "TotalInstruments={}",
                batches.size(),
                MAX_INSTRUMENTS_PER_REQUEST,
                instruments.size());

        return Flux.fromIterable(batches)
                .concatMap(this::processBatch)
                .collectList()
                .map(this::flatten)

                .doOnNext(stocks ->
                        log.info(
                                "Trending Stock Scan | " +
                                        "Complete Scan ResultCount={}",
                                stocks.size()));
    }

    /**
     * Existing Trending Stock behavior.
     *
     * IMPORTANT:
     * The public getTrendingStocks() behavior remains unchanged.
     *
     * We scan the complete universe first and only then apply
     * the requested top-N trending limit.
     */
    private Mono<List<TrendingStock>> scanInBatches(
            List<TradableInstrumentResponse> instruments,
            int topN) {

        return scanAllInBatches(instruments)

                .map(stocks ->
                        stocks.stream()
                                .filter(Objects::nonNull)

                                .sorted(
                                        Comparator.comparing(
                                                TrendingStock::getTrendingScore,
                                                Comparator.nullsLast(
                                                        Comparator.reverseOrder())))

                                .limit(topN)

                                .toList())

                .doOnNext(this::logResults);
    }

    /**
     * Builds a single midday market snapshot.
     *
     * This performs ONE complete market scan and derives:
     *
     * - Top Gainers
     * - Top Losers
     * - Top Trending Stocks
     *
     * Observation-only.
     *
     * This method does NOT:
     *
     * - generate BUY/SELL signals
     * - call AI
     * - call Risk Guard
     * - create paper trades
     */
    public Mono<MiddayMarketSnapshot> getMiddayMarketSnapshot() {

        final int topN = DEFAULT_TOP_RESULTS;

        log.info(
                "======================================================");

        log.info(
                "MIDDAY MARKET DISCOVERY STARTED | TopN={}",
                topN);

        log.info(
                "======================================================");

        return stockServiceClient
                .getTradableEquities()
                .collectList()

                .flatMap(instruments -> {

                    if (instruments == null
                            || instruments.isEmpty()) {

                        log.warn(
                                "MIDDAY MARKET DISCOVERY | " +
                                        "No tradable instruments found");

                        return Mono.just(
                                MiddayMarketSnapshot.builder()
                                        .timestamp(Instant.now())
                                        .topGainers(List.of())
                                        .topLosers(List.of())
                                        .trendingStocks(List.of())
                                        .totalCandidates(0)
                                        .build());
                    }

                    log.info(
                            "MIDDAY MARKET DISCOVERY | " +
                                    "Tradable Instruments={}",
                            instruments.size());

                    /*
                     * IMPORTANT:
                     *
                     * Midday discovery must scan the COMPLETE
                     * tradable universe.
                     *
                     * Do NOT call scanInBatches() here because
                     * that method applies the existing top-N
                     * trending limit.
                     */
                    return scanAllInBatches(instruments)

                            .map(allStocks ->
                                    buildMiddaySnapshot(
                                            allStocks,
                                            topN));
                })

                .doOnSuccess(snapshot -> {

                    if (snapshot == null) {
                        return;
                    }

                    log.info(
                            "MIDDAY MARKET DISCOVERY COMPLETED | " +
                                    "Gainers={} | " +
                                    "Losers={} | " +
                                    "Trending={} | " +
                                    "TotalCandidates={}",
                            snapshot.getTopGainers().size(),
                            snapshot.getTopLosers().size(),
                            snapshot.getTrendingStocks().size(),
                            snapshot.getTotalCandidates());
                })

                .doOnError(error ->
                        log.error(
                                "MIDDAY MARKET DISCOVERY FAILED",
                                error));
    }

    private MiddayMarketSnapshot buildMiddaySnapshot(
            List<TrendingStock> stocks,
            int topN) {

        if (stocks == null || stocks.isEmpty()) {

            return MiddayMarketSnapshot.builder()
                    .timestamp(Instant.now())
                    .topGainers(List.of())
                    .topLosers(List.of())
                    .trendingStocks(List.of())
                    .totalCandidates(0)
                    .build();
        }


        /*
         * ============================================================
         * TOP GAINERS
         *
         * Highest positive percentage movers.
         * ============================================================
         */

        List<TrendingStock> topGainers =
                stocks.stream()
                        .filter(stock ->
                                stock != null
                                        && stock.getChangePercentage() != null
                                        && stock.getChangePercentage() > 0)

                        .sorted(
                                Comparator.comparing(
                                        TrendingStock::getChangePercentage,
                                        Comparator.reverseOrder()))

                        .limit(topN)

                        .toList();


        /*
         * ============================================================
         * TOP LOSERS
         *
         * Largest negative percentage movers.
         * ============================================================
         */

        List<TrendingStock> topLosers =
                stocks.stream()
                        .filter(stock ->
                                stock != null
                                        && stock.getChangePercentage() != null
                                        && stock.getChangePercentage() < 0)

                        .sorted(
                                Comparator.comparing(
                                        TrendingStock::getChangePercentage))

                        .limit(topN)

                        .toList();


        /*
         * ============================================================
         * TOP TRENDING
         *
         * Uses the existing TrendingScoreCalculator output.
         * ============================================================
         */

        List<TrendingStock> trendingStocks =
                stocks.stream()
                        .filter(stock ->
                                stock != null
                                        && stock.getTrendingScore() != null)

                        .sorted(
                                Comparator.comparing(
                                        TrendingStock::getTrendingScore,
                                        Comparator.reverseOrder()))

                        .limit(topN)

                        .toList();


        /*
         * ============================================================
         * DISCOVERY SUMMARY
         * ============================================================
         */

        log.info(
                "MIDDAY DISCOVERY | " +
                        "Universe={} | " +
                        "TopGainers={} | " +
                        "TopLosers={} | " +
                        "Trending={}",
                stocks.size(),
                topGainers.size(),
                topLosers.size(),
                trendingStocks.size());


        /*
         * ============================================================
         * TOP GAINER DIAGNOSTICS
         * ============================================================
         */

        topGainers.forEach(stock ->
                log.info(
                        "MIDDAY GAINER | " +
                                "Symbol={} | " +
                                "Change={}%% | " +
                                "Volume={} | " +
                                "TrendScore={}",
                        stock.getTradingSymbol(),
                        stock.getChangePercentage(),
                        stock.getVolume(),
                        stock.getTrendingScore()));


        /*
         * ============================================================
         * TOP LOSER DIAGNOSTICS
         * ============================================================
         */

        topLosers.forEach(stock ->
                log.info(
                        "MIDDAY LOSER | " +
                                "Symbol={} | " +
                                "Change={}%% | " +
                                "Volume={} | " +
                                "TrendScore={}",
                        stock.getTradingSymbol(),
                        stock.getChangePercentage(),
                        stock.getVolume(),
                        stock.getTrendingScore()));


        /*
         * ============================================================
         * TRENDING DIAGNOSTICS
         * ============================================================
         */

        trendingStocks.forEach(stock ->
                log.info(
                        "MIDDAY TRENDING | " +
                                "Symbol={} | " +
                                "Score={} | " +
                                "Direction={} | " +
                                "Change={}%%",
                        stock.getTradingSymbol(),
                        stock.getTrendingScore(),
                        stock.getTrendDirection(),
                        stock.getChangePercentage()));


        return MiddayMarketSnapshot.builder()
                .timestamp(Instant.now())
                .topGainers(topGainers)
                .topLosers(topLosers)
                .trendingStocks(trendingStocks)
                .totalCandidates(stocks.size())
                .build();
    }

    /**
     * Processes one batch of instruments.
     */
    private Mono<List<TrendingStock>> processBatch(
            List<TradableInstrumentResponse> instruments) {

        List<String> instrumentKeys =
                instruments.stream()
                        .map(TradableInstrumentResponse::getInstrumentKey)
                        .filter(key ->
                                key != null && !key.isBlank())
                        .distinct()
                        .toList();

        if (instrumentKeys.isEmpty()) {
            return Mono.just(List.of());
        }

        Map<String, TradableInstrumentResponse> instrumentMap =
                new HashMap<>();

        for (TradableInstrumentResponse instrument : instruments) {

            if (instrument.getInstrumentKey() == null
                    || instrument.getInstrumentKey().isBlank()) {
                continue;
            }

            instrumentMap.putIfAbsent(
                    instrument.getInstrumentKey(),
                    instrument);
        }

        return upstoxMarketClient
                .getQuotes(instrumentKeys)
                .map(response ->
                        convertQuotes(
                                response,
                                instrumentMap));
    }

    /**
     * Converts Upstox market quotes into TrendingStock results.
     */
    private List<TrendingStock> convertQuotes(
            WatchlistMarketQuoteResponse response,
            Map<String, TradableInstrumentResponse> instrumentMap) {

        if (response == null
                || response.getData() == null
                || response.getData().isEmpty()) {

            log.warn(
                    "Trending Stock Scan | No quote data received from Upstox");

            return List.of();
        }

        log.info(
                "Trending Stock Scan | Upstox Quotes Received={}",
                response.getData().size());

        List<TrendingStock> results =
                new ArrayList<>();

        long timestamp =
                Instant.now().toEpochMilli();

        response.getData()
                .forEach((responseKey, quote) -> {

                    if (quote == null
                            || quote.getLastPrice() == null) {

                        return;
                    }

                    /*
                     * IMPORTANT:
                     *
                     * Upstox response map key can be:
                     *
                     * NSE_EQ:NHPC
                     *
                     * while the actual instrument key is:
                     *
                     * NSE_EQ|INE848E01016
                     *
                     * The quote's instrument_token is therefore
                     * the reliable identifier for our mapping.
                     */
                    String instrumentKey =
                            quote.getInstrumentToken();

                    if (instrumentKey == null
                            || instrumentKey.isBlank()) {

                        log.warn(
                                "Trending Stock Scan | Missing instrument_token | ResponseKey={}",
                                responseKey);

                        return;
                    }

                    TradableInstrumentResponse instrument =
                            instrumentMap.get(instrumentKey);

                    if (instrument == null) {

                        log.warn(
                                "Trending Stock Scan | Instrument Mapping Failed | InstrumentKey={} | ResponseKey={}",
                                instrumentKey,
                                responseKey);

                        return;
                    }

                    TrendingMarketSnapshot current =
                            createSnapshot(
                                    instrument,
                                    quote,
                                    timestamp);

                    TrendingMarketSnapshot previous =
                            previousSnapshots.get(instrumentKey);

                    double score =
                            trendingScoreCalculator.calculateScore(
                                    current,
                                    previous);

                    String direction =
                            determineTrendDirection(
                                    current,
                                    previous);

                    String level =
                            determineTrendingLevel(score);

                    TrendingStock trendingStock =
                            TrendingStock.builder()
                                    .tradingSymbol(
                                            instrument.getTradingSymbol())
                                    .companyName(
                                            instrument.getCompanyName())
                                    .instrumentKey(
                                            instrument.getInstrumentKey())
                                    .lastPrice(
                                            current.getLastPrice())
                                    .changePercentage(
                                            current.getChangePercentage())
                                    .volume(
                                            current.getVolume())
                                    .trendingScore(score)
                                    .trendDirection(direction)
                                    .trendingLevel(level)
                                    .build();

                    results.add(trendingStock);

                    /*
                     * Store current snapshot AFTER calculating
                     * the score.
                     */
                    previousSnapshots.put(
                            instrumentKey,
                            current);
                });

        log.info(
                "Trending Stock Scan | Successfully Converted Quotes={}",
                results.size());

        return results;
    }

    /**
     * Converts the actual WatchlistQuoteData structure
     * into our internal TrendingMarketSnapshot.
     */
    private TrendingMarketSnapshot createSnapshot(
            TradableInstrumentResponse instrument,
            WatchlistQuoteData quote,
            long timestamp) {

        WatchlistOhlc ohlc =
                quote.getOhlc();

        Double open = null;
        Double high = null;
        Double low = null;
        Double close = null;

        if (ohlc != null) {

            open = ohlc.getOpen();
            high = ohlc.getHigh();
            low = ohlc.getLow();
            close = ohlc.getClose();
        }

        Double changePercentage = null;

        if (quote.getLastPrice() != null
                && quote.getNetChange() != null) {

            double previousClose =
                    quote.getLastPrice()
                            - quote.getNetChange();

            if (previousClose > 0) {

                changePercentage =
                        (quote.getNetChange()
                                / previousClose)
                                * 100.0;
            }
        }

        return TrendingMarketSnapshot.builder()
                .tradingSymbol(
                        instrument.getTradingSymbol())
                .companyName(
                        instrument.getCompanyName())
                .instrumentKey(
                        instrument.getInstrumentKey())
                .lastPrice(
                        quote.getLastPrice())
                .changePercentage(
                        changePercentage)
                .volume(
                        quote.getVolume())
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .averagePrice(
                        quote.getAveragePrice())
                .timestamp(timestamp)
                .build();
    }

    /**
     * Determines current trend direction.
     */
    private String determineTrendDirection(
            TrendingMarketSnapshot current,
            TrendingMarketSnapshot previous) {

        if (current == null
                || current.getLastPrice() == null) {

            return "NEUTRAL";
        }

        if (previous != null
                && previous.getLastPrice() != null) {

            if (current.getLastPrice()
                    > previous.getLastPrice()) {

                return "BULLISH";
            }

            if (current.getLastPrice()
                    < previous.getLastPrice()) {

                return "BEARISH";
            }
        }

        if (current.getChangePercentage() != null) {

            if (current.getChangePercentage() > 0) {
                return "BULLISH";
            }

            if (current.getChangePercentage() < 0) {
                return "BEARISH";
            }
        }

        return "NEUTRAL";
    }

    /**
     * Converts score into a readable trending level.
     */
    private String determineTrendingLevel(
            double score) {

        if (score >= 80.0) {
            return "STRONG_TREND";
        }

        if (score >= 65.0) {
            return "TRENDING";
        }

        if (score >= 50.0) {
            return "WATCH";
        }

        return "IGNORE";
    }

    /**
     * Flattens batch results.
     */
    private List<TrendingStock> flatten(
            List<List<TrendingStock>> batchResults) {

        return batchResults.stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * Splits the universe into batches.
     */
    private <T> List<List<T>> partition(
            List<T> source,
            int batchSize) {

        List<List<T>> result =
                new ArrayList<>();

        for (int i = 0;
             i < source.size();
             i += batchSize) {

            result.add(
                    source.subList(
                            i,
                            Math.min(
                                    i + batchSize,
                                    source.size())));
        }

        return result;
    }

    /**
     * Logs final ranked trending stocks.
     */
    private void logResults(
            List<TrendingStock> results) {

        log.info(
                "Trending Stock Scan Completed | ResultCount={}",
                results.size());

        results.forEach(stock ->
                log.info(
                        "TRENDING | Symbol={} | Score={} | Direction={} | Level={} | Change={}%",
                        stock.getTradingSymbol(),
                        stock.getTrendingScore(),
                        stock.getTrendDirection(),
                        stock.getTrendingLevel(),
                        stock.getChangePercentage()));
    }
}