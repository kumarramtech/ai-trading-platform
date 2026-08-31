package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.premarket.PreMarketCandidate;
import com.ram.trading.signal.engine.service.interfac.PreMarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreMarketAnalysisService {

    private static final LocalTime PRE_MARKET_START =
            LocalTime.of(9, 0);

    private static final LocalTime MARKET_OPEN =
            LocalTime.of(9, 15);

    private static final double MIN_GAP_PERCENT =
            0.50;

    private static final double STRONG_GAP_PERCENT =
            1.50;

    private static final int MAX_CANDIDATES =
            20;

    private final PreMarketCandidateStore preMarketCandidateStore;

    private final PreMarketDataProvider preMarketDataProvider;


    /**
     * Performs pre-market analysis between 09:00 and 09:15 IST.
     *
     * This service only discovers candidates.
     *
     * It does NOT:
     * - generate BUY/SELL
     * - call AI
     * - create paper trades
     * - create portfolio positions
     */
    public Mono<List<PreMarketCandidate>> analyze() {

        LocalTime now = LocalTime.now();

        if (now.isBefore(PRE_MARKET_START)
                || !now.isBefore(MARKET_OPEN)) {

            log.info(
                    "PRE-MARKET ANALYSIS SKIPPED | " +
                            "CurrentTime={}",
                    now
            );

            return Mono.just(List.of());
        }

        log.info("======================================================");
        log.info("V2.2 PRE-MARKET ANALYSIS STARTED");
        log.info("Window : 09:00 - 09:15 IST");
        log.info("======================================================");

        return preMarketDataProvider
                .getCandidates()

                .switchIfEmpty(
                        Mono.just(List.of())
                )

                .flatMapMany(
                        Flux::fromIterable
                )

                .flatMap(
                        this::evaluateCandidate,
                        5
                )

                .filter(candidate ->
                        candidate != null
                )

                .sort(
                        Comparator.comparingInt(
                                PreMarketCandidate::getScore
                        ).reversed()
                )

                .take(MAX_CANDIDATES)

                .collectList()

                .doOnNext(candidates -> {

                    preMarketCandidateStore.store(
                            candidates
                    );

                    log.info(
                            "PRE-MARKET CANDIDATES STORED | " +
                                    "Count={}",
                            candidates.size()
                    );
                })

                .doOnSuccess(candidates ->
                        log.info(
                                "V2.2 PRE-MARKET ANALYSIS COMPLETED | " +
                                        "Candidates={}",
                                candidates.size()
                        )
                )

                .doOnError(error ->
                        log.error(
                                "V2.2 PRE-MARKET ANALYSIS FAILED",
                                error
                        )
                );
    }


    private Mono<PreMarketCandidate> evaluateCandidate(
            PreMarketDataProvider.PreMarketQuote quote) {

        if (quote == null) {
            return Mono.empty();
        }

        String symbol =
                quote.getTradingSymbol();

        Double previousClose =
                quote.getPreviousClose();

        Double preMarketPrice =
                quote.getPreMarketPrice();

        if (symbol == null
                || symbol.isBlank()
                || previousClose == null
                || previousClose <= 0
                || preMarketPrice == null
                || preMarketPrice <= 0) {

            log.debug(
                    "PRE-MARKET CANDIDATE SKIPPED | " +
                            "Invalid market data | " +
                            "Symbol={} | PreviousClose={} | " +
                            "PreMarketPrice={}",
                    symbol,
                    previousClose,
                    preMarketPrice
            );

            return Mono.empty();
        }

        double gapPercentage =
                ((preMarketPrice - previousClose)
                        / previousClose) * 100.0;

        /*
         * Ignore insignificant gaps.
         */
        if (Math.abs(gapPercentage)
                < MIN_GAP_PERCENT) {

            return Mono.empty();
        }

        String direction =
                gapPercentage > 0
                        ? "BULLISH"
                        : "BEARISH";

        int score =
                calculateScore(
                        gapPercentage,
                        quote
                );

        String status =
                determineStatus(score);

        PreMarketCandidate candidate =
                PreMarketCandidate.builder()
                        .tradingSymbol(symbol)
                        .previousClose(previousClose)
                        .preMarketPrice(preMarketPrice)
                        .gapPercentage(gapPercentage)
                        .direction(direction)
                        .score(score)
                        .status(status)
                        .discoveredAt(
                                LocalDateTime.now()
                        )
                        .build();

        log.info(
                "PRE-MARKET CANDIDATE | " +
                        "Symbol={} | PreviousClose={} | " +
                        "PreMarketPrice={} | Gap={} | " +
                        "Direction={} | Score={} | Status={}",
                symbol,
                previousClose,
                preMarketPrice,
                gapPercentage,
                direction,
                score,
                status
        );

        return Mono.just(candidate);
    }


    private int calculateScore(
            double gapPercentage,
            PreMarketDataProvider.PreMarketQuote quote) {

        int score = 0;

        if (Math.abs(gapPercentage)
                >= STRONG_GAP_PERCENT) {

            score += 2;

        } else if (Math.abs(gapPercentage)
                >= MIN_GAP_PERCENT) {

            score += 1;
        }

        if (quote.isReliable()) {
            score += 1;
        }

        if (quote.getPreMarketVolume() != null
                && quote.getPreMarketVolume() > 0) {

            score += 1;
        }

        if (quote.getPreviousVolume() != null
                && quote.getPreviousVolume() > 0
                && quote.getPreMarketVolume() != null
                && quote.getPreMarketVolume()
                >= quote.getPreviousVolume() * 0.10) {

            score += 1;
        }

        return score;
    }


    private String determineStatus(int score) {

        if (score >= 4) {
            return "STRONG";
        }

        if (score >= 2) {
            return "MODERATE";
        }

        return "WEAK";
    }
}