package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.history.HistoricalPriceResponse;
import com.ram.trading.signal.engine.service.interfac.PreMarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreMarketDataProviderImpl
        implements PreMarketDataProvider {

    private final StockServiceClient stockServiceClient;


    @Override
    public Mono<List<PreMarketQuote>> getCandidates() {

        log.info(
                "PRE-MARKET DATA PROVIDER | " +
                        "Loading available stocks"
        );

        return stockServiceClient
                .getAllStocks()

                /*
                 * Process a controlled number of stocks
                 * concurrently.
                 */
                .flatMap(
                        this::buildQuote,
                        5
                )

                /*
                 * Remove unavailable candidates.
                 */
                .filter(quote -> quote != null)

                .collectList()

                .doOnSuccess(quotes ->
                        log.info(
                                "PRE-MARKET DATA PROVIDER | " +
                                        "Valid quotes={}",
                                quotes.size()
                        )
                )

                .doOnError(error ->
                        log.error(
                                "PRE-MARKET DATA PROVIDER | " +
                                        "Failed to load candidates",
                                error
                        )
                );
    }


    /**
     * Builds one pre-market quote using:
     *
     * 1. Current available stock price
     * 2. Latest previous trading-day close
     */
    private Mono<PreMarketQuote> buildQuote(
            StockResponse stock) {

        if (stock == null
                || stock.getSymbol() == null
                || stock.getSymbol().isBlank()) {

            return Mono.empty();
        }

        String symbol =
                stock.getSymbol();

        Double currentPrice =
                stock.getPrice();

        /*
         * No current quote means we cannot calculate
         * a meaningful gap.
         */
        if (currentPrice == null
                || currentPrice <= 0) {

            log.debug(
                    "PRE-MARKET DATA | " +
                            "Current price unavailable | " +
                            "Symbol={}",
                    symbol
            );

            return Mono.empty();
        }

        /*
         * Get historical prices to determine the
         * latest valid previous trading-day close.
         */
        return stockServiceClient
                .getHistoricalPrices(symbol)

                .collectList()

                .flatMap(prices ->
                        buildQuoteFromHistory(
                                symbol,
                                currentPrice,
                                prices
                        )
                )

                .onErrorResume(error -> {

                    log.warn(
                            "PRE-MARKET DATA | " +
                                    "Historical data unavailable | " +
                                    "Symbol={} | ErrorType={} | Error={}",
                            symbol,
                            error.getClass()
                                    .getSimpleName(),
                            error.getMessage()
                    );

                    return Mono.empty();
                });
    }


    /**
     * Creates the provider quote from historical data.
     */
    private Mono<PreMarketQuote> buildQuoteFromHistory(
            String symbol,
            Double currentPrice,
            List<HistoricalPriceResponse> prices) {

        if (prices == null
                || prices.isEmpty()) {

            log.debug(
                    "PRE-MARKET DATA | " +
                            "No historical prices | Symbol={}",
                    symbol
            );

            return Mono.empty();
        }

        /*
         * Find the most recent valid historical
         * trading-day record.
         */
        HistoricalPriceResponse latest =
                prices.stream()

                        .filter(price ->
                                price != null
                                        && price.getTradeDate() != null
                                        && price.getClosePrice() != null
                                        && price.getClosePrice()
                                        .doubleValue() > 0
                        )

                        .filter(price ->
                                !price.getTradeDate()
                                        .equals(LocalDate.now())
                        )

                        .max(
                                Comparator.comparing(
                                        HistoricalPriceResponse::getTradeDate
                                )
                        )

                        .orElse(null);

        if (latest == null) {

            log.debug(
                    "PRE-MARKET DATA | " +
                            "No valid previous close | Symbol={}",
                    symbol
            );

            return Mono.empty();
        }

        double previousClose =
                latest.getClosePrice()
                        .doubleValue();

        Long previousVolume =
                latest.getVolume();

        /*
         * IMPORTANT:
         *
         * The Stock Service currently exposes the latest
         * stock price through /stocks/{symbol}.
         *
         * We preserve that value here as the available
         * quote. We do NOT fabricate a price.
         */
        PreMarketQuote quote =
                PreMarketDataProvider.PreMarketQuote.builder()
                        .tradingSymbol(symbol)
                        .previousClose(previousClose)
                        .preMarketPrice(currentPrice)
                        .preMarketVolume(null)
                        .previousVolume(previousVolume)
                        .reliable(
                                previousClose > 0
                                        && currentPrice > 0
                        )
                        .build();

        log.debug(
                "PRE-MARKET DATA | " +
                        "Quote prepared | " +
                        "Symbol={} | PreviousClose={} | " +
                        "AvailablePrice={} | PreviousVolume={}",
                symbol,
                previousClose,
                currentPrice,
                previousVolume
        );

        return Mono.just(quote);
    }
}