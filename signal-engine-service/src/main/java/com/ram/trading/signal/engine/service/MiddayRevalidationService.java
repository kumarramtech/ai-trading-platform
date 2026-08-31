package com.ram.trading.signal.engine.service;


import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.watchlist.MiddayRevalidationResult;
import com.ram.trading.signal.engine.dto.watchlist.TrendingStockResponse;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MiddayRevalidationService {

    private final MiddayCandidateStore middayCandidateStore;

    private final MarketDataProvider marketDataProvider;

    private final TechnicalIndicatorService technicalIndicatorService;


    /**
     * Revalidates candidates discovered at 11:30 AM.
     * This service is a QUALITY GATE only.
     * It does NOT:
     * - generate BUY/SELL
     * - call AI
     * - call Risk Guard
     * - create paper trades
     */
    public Mono<List<MiddayRevalidationResult>> revalidateCandidates() {

        List<TrendingStockResponse> candidates =
                middayCandidateStore.getCandidates();

        LocalDateTime discoveredAt =
                middayCandidateStore.getDiscoveredAt();

        log.info("======================================================");
        log.info("V2.2 MIDDAY REVALIDATION STARTED");
        log.info("Candidates   : {}", candidates.size());
        log.info("Discovered At: {}", discoveredAt);
        log.info("======================================================");

        if (candidates.isEmpty()) {

            log.warn(
                    "MIDDAY REVALIDATION | No candidates available"
            );

            return Mono.just(List.of());
        }

        /*
         * Never allow a previous trading day's candidates
         * to enter today's trading pipeline.
         */
        if (discoveredAt == null
                || !discoveredAt.toLocalDate()
                .equals(LocalDate.now())) {

            log.warn(
                    "MIDDAY REVALIDATION | STALE SNAPSHOT | " +
                            "DiscoveredAt={}",
                    discoveredAt
            );

            return Mono.just(List.of());
        }

        return Flux.fromIterable(candidates)

                /*
                 * Keep concurrency controlled.
                 *
                 * We don't want 49 simultaneous requests hitting
                 * the market-data / indicator services.
                 */
                .flatMap(
                        this::revalidateCandidate,
                        5
                )

                .collectList()

                .doOnSuccess(results -> {

                    long qualified =
                            results.stream()
                                    .filter(r ->
                                            "QUALIFIED".equals(r.getStatus()))
                                    .count();

                    long weakened =
                            results.stream()
                                    .filter(r ->
                                            "WEAKENED".equals(r.getStatus()))
                                    .count();

                    long rejected =
                            results.stream()
                                    .filter(r ->
                                            "REJECTED".equals(r.getStatus()))
                                    .count();

                    log.info(
                            "======================================================"
                    );

                    log.info(
                            "V2.2 MIDDAY REVALIDATION COMPLETED"
                    );

                    log.info(
                            "Total    : {}",
                            results.size()
                    );

                    log.info(
                            "Qualified: {}",
                            qualified
                    );

                    log.info(
                            "Weakened : {}",
                            weakened
                    );

                    log.info(
                            "Rejected : {}",
                            rejected
                    );

                    log.info(
                            "======================================================"
                    );
                })

                .doOnError(error ->
                        log.error(
                                "V2.2 MIDDAY REVALIDATION FAILED",
                                error
                        ));
    }


    /**
     * Revalidate one candidate.
     */
    /**
     * Revalidate one candidate.
     *
     * This method is a quality-gate only.
     * It preserves the original discovery information and
     * clearly identifies where revalidation failed.
     */
    private Mono<MiddayRevalidationResult> revalidateCandidate(
            TrendingStockResponse candidate) {

        String symbol = candidate.getTradingSymbol();

        Double discoveryPrice = candidate.getLastPrice();

        /*
         * Always preserve the original discovery information.
         */
        if (symbol == null || symbol.isBlank()) {

            return Mono.just(
                    rejected(
                            candidate,
                            "INVALID_SYMBOL",
                            null
                    )
            );
        }

        if (discoveryPrice == null || discoveryPrice <= 0) {

            log.warn(
                    "MIDDAY REVALIDATION | " +
                            "INVALID DISCOVERY PRICE | " +
                            "Symbol={} | Price={}",
                    symbol,
                    discoveryPrice
            );

            return Mono.just(
                    rejected(
                            candidate,
                            "INVALID_DISCOVERY_PRICE",
                            null
                    )
            );
        }

        /*
         * =========================================================
         * STEP 1: FETCH CURRENT MARKET PRICE
         * =========================================================
         */
        return marketDataProvider
                .getStockPrice(symbol)

                /*
                 * Explicitly identify market-data failures.
                 */
                .doOnError(error ->
                        log.error(
                                "MIDDAY REVALIDATION | " +
                                        "CURRENT PRICE FAILED | " +
                                        "Symbol={} | ErrorType={} | Error={}",
                                symbol,
                                error.getClass().getSimpleName(),
                                error.getMessage(),
                                error
                        )
                )

                .flatMap(stock -> {

                    /*
                     * Defensive validation.
                     */
                    if (stock == null
                            || stock.getPrice() <= 0) {

                        log.warn(
                                "MIDDAY REVALIDATION | " +
                                        "CURRENT PRICE INVALID | " +
                                        "Symbol={} | CurrentPrice={}",
                                symbol,
                                stock == null
                                        ? null
                                        : stock.getPrice()
                        );

                        return Mono.just(
                                rejected(
                                        candidate,
                                        "CURRENT_PRICE_UNAVAILABLE",
                                        null
                                )
                        );
                    }

                    double currentPrice =
                            stock.getPrice();

                    log.debug(
                            "MIDDAY REVALIDATION | " +
                                    "CURRENT PRICE RECEIVED | " +
                                    "Symbol={} | DiscoveryPrice={} | CurrentPrice={}",
                            symbol,
                            discoveryPrice,
                            currentPrice
                    );

                    /*
                     * =================================================
                     * STEP 2: TECHNICAL INDICATORS
                     * =================================================
                     */
                    return technicalIndicatorService
                            .calculate(symbol)

                            /*
                             * Explicitly identify indicator failures.
                             */
                            .doOnError(error ->
                                    log.error(
                                            "MIDDAY REVALIDATION | " +
                                                    "INDICATOR CALCULATION FAILED | " +
                                                    "Symbol={} | ErrorType={} | Error={}",
                                            symbol,
                                            error.getClass().getSimpleName(),
                                            error.getMessage(),
                                            error
                                    )
                            )

                            .map(indicators -> {

                                /*
                                 * Defensive validation.
                                 */
                                if (indicators == null) {

                                    log.warn(
                                            "MIDDAY REVALIDATION | " +
                                                    "INDICATORS NULL | " +
                                                    "Symbol={}",
                                            symbol
                                    );

                                    return rejected(
                                            candidate,
                                            "TECHNICAL_INDICATORS_UNAVAILABLE",
                                            currentPrice
                                    );
                                }

                                log.debug(
                                        "MIDDAY REVALIDATION | " +
                                                "INDICATORS RECEIVED | " +
                                                "Symbol={} | RSI={} | EMA20={} | EMA50={} | MACD={} | Signal={}",
                                        symbol,
                                        indicators.getRsi14(),
                                        indicators.getEma20(),
                                        indicators.getEma50(),
                                        indicators.getMacd(),
                                        indicators.getSignalLine()
                                );

                                /*
                                 * =================================================
                                 * STEP 3: ACTUAL V2.2 QUALITY EVALUATION
                                 * =================================================
                                 */
                                return evaluateCandidate(
                                        candidate,
                                        currentPrice,
                                        indicators
                                );
                            })

                            /*
                             * Technical indicator service returned
                             * Mono.empty().
                             */
                            .switchIfEmpty(
                                    Mono.fromSupplier(() -> {

                                        log.warn(
                                                "MIDDAY REVALIDATION | " +
                                                        "TECHNICAL INDICATORS EMPTY | " +
                                                        "Symbol={}",
                                                symbol
                                        );

                                        return rejected(
                                                candidate,
                                                "TECHNICAL_INDICATORS_UNAVAILABLE",
                                                currentPrice
                                        );
                                    })
                            )

                            /*
                             * Important:
                             * Do NOT hide the candidate's discovery data.
                             */
                            .onErrorResume(error -> {

                                log.error(
                                        "MIDDAY REVALIDATION | " +
                                                "INDICATOR STAGE FAILED | " +
                                                "Symbol={} | ErrorType={} | Error={}",
                                        symbol,
                                        error.getClass().getSimpleName(),
                                        error.getMessage(),
                                        error
                                );

                                return Mono.just(
                                        rejected(
                                                candidate,
                                                determineIndicatorFailureReason(error),
                                                currentPrice
                                        )
                                );
                            });
                })

                /*
                 * Current price service returned Mono.empty().
                 */
                .switchIfEmpty(
                        Mono.fromSupplier(() -> {

                            log.warn(
                                    "MIDDAY REVALIDATION | " +
                                            "CURRENT PRICE EMPTY | " +
                                            "Symbol={}",
                                    symbol
                            );

                            return rejected(
                                    candidate,
                                    "CURRENT_PRICE_UNAVAILABLE",
                                    null
                            );
                        })
                )

                /*
                 * Final defensive protection.
                 *
                 * If something unexpected happens outside the
                 * explicitly handled stages, preserve the candidate
                 * and expose the actual exception type.
                 */
                .onErrorResume(error -> {

                    log.error(
                            "MIDDAY REVALIDATION | " +
                                    "UNEXPECTED FAILURE | " +
                                    "Symbol={} | ErrorType={} | Error={}",
                            symbol,
                            error.getClass().getSimpleName(),
                            error.getMessage(),
                            error
                    );

                    return Mono.just(
                            rejected(
                                    candidate,
                                    determineFailureReason(error),
                                    null
                            )
                    );
                });
    }

    private String determineIndicatorFailureReason(
            Throwable error) {

        if (error == null) {
            return "TECHNICAL_INDICATORS_ERROR";
        }

        String message = error.getMessage();

        if (message == null || message.isBlank()) {
            return "TECHNICAL_INDICATORS_ERROR";
        }

        String normalized =
                message.toLowerCase();

        if (normalized.contains("timeout")
                || normalized.contains("timed out")) {

            return "TECHNICAL_INDICATORS_TIMEOUT";
        }

        if (normalized.contains("connection")
                || normalized.contains("connect")) {

            return "TECHNICAL_INDICATORS_CONNECTION_ERROR";
        }

        return "TECHNICAL_INDICATORS_ERROR";
    }

    /**
     * Performs the actual quality evaluation.
     */
    private MiddayRevalidationResult evaluateCandidate(
            TrendingStockResponse candidate,
            double currentPrice,
            TechnicalIndicatorResponse indicators) {

        String symbol =
                candidate.getTradingSymbol();

        double discoveryPrice =
                candidate.getLastPrice();

        String direction =
                determineDirection(candidate);

        double movementPct =
                calculateDirectionalMovement(
                        discoveryPrice,
                        currentPrice,
                        direction
                );

        int score = 0;

        /*
         * 1. PRICE CONTINUATION
         */
        if (isPriceContinuation(movementPct, direction)) {
            score += 2;
        }

        /*
         * 2. TREND STRUCTURE
         */
        if (hasTrendStructure(
                currentPrice,
                indicators,
                direction)) {

            score += 2;
        }

        /*
         * 3. MACD
         */
        if (hasMacdConfirmation(
                indicators,
                direction)) {

            score += 1;
        }

        /*
         * 4. RSI
         */
        if (hasHealthyRsi(
                indicators,
                direction)) {

            score += 1;
        }

        /*
         * 5. DISCOVERY STRENGTH
         */
        if (hasDiscoveryStrength(
                candidate,
                direction)) {

            score += 1;
        }

        /*
         * HARD REVERSAL
         */
        if (isHardReversal(
                movementPct)) {

            log.info(
                    "MIDDAY REVALIDATION | " +
                            "Hard reversal | Symbol={} | " +
                            "MovementPct={}",
                    symbol,
                    movementPct
            );

            return MiddayRevalidationResult.builder()
                    .tradingSymbol(symbol)
                    .direction(direction)
                    .discoveryPrice(discoveryPrice)
                    .currentPrice(currentPrice)
                    .directionalMovementPct(movementPct)
                    .rsi(indicators.getRsi14())
                    .ema20(indicators.getEma20())
                    .ema50(indicators.getEma50())
                    .macd(indicators.getMacd())
                    .signalLine(indicators.getSignalLine())
                    .score(0)
                    .status("REJECTED")
                    .reason("DIRECTIONAL_REVERSAL")
                    .build();
        }

        String status =
                determineStatus(score);

        String reason =
                buildReason(
                        movementPct,
                        direction,
                        score,
                        status
                );

        return MiddayRevalidationResult.builder()
                .tradingSymbol(symbol)
                .direction(direction)
                .discoveryPrice(discoveryPrice)
                .currentPrice(currentPrice)
                .directionalMovementPct(movementPct)
                .rsi(indicators.getRsi14())
                .ema20(indicators.getEma20())
                .ema50(indicators.getEma50())
                .macd(indicators.getMacd())
                .signalLine(indicators.getSignalLine())
                .score(score)
                .status(status)
                .reason(reason)
                .build();
    }


    /**
     * Price continuation.
     * We don't require a fixed +0.25% threshold.
     * The important distinction is:
     * positive movement  = continuation
     * negative movement  = deterioration
     */
    private boolean isPriceContinuation(
            double movementPct,
            String direction) {

        if ("BULLISH".equals(direction)) {
            return movementPct > 0;
        }

        if ("BEARISH".equals(direction)) {
            return movementPct > 0;
        }

        return false;
    }


    /**
     * Trend structure confirmation.
     * Bullish:
     * price > EMA20 > EMA50
     * Bearish:
     * price < EMA20 < EMA50
     */
    private boolean hasTrendStructure(
            double currentPrice,
            TechnicalIndicatorResponse indicators,
            String direction) {

        Double ema20 =
                indicators.getEma20();

        Double ema50 =
                indicators.getEma50();

        if (ema20 == null || ema50 == null) {
            return false;
        }

        if ("BULLISH".equals(direction)) {

            return currentPrice > ema20
                    && ema20 > ema50;
        }

        if ("BEARISH".equals(direction)) {

            return currentPrice < ema20
                    && ema20 < ema50;
        }

        return false;
    }


    /**
     * MACD confirmation.
     * We use the sign of MACD relative to zero.
     * This intentionally remains simple because the existing
     * TechnicalIndicatorResponse currently exposes MACD directly.
     */
    private boolean hasMacdConfirmation(
            TechnicalIndicatorResponse indicators,
            String direction) {

        Double macd =
                indicators.getMacd();

        if (macd == null) {
            return false;
        }

        if ("BULLISH".equals(direction)) {
            return macd > 0;
        }

        if ("BEARISH".equals(direction)) {
            return macd < 0;
        }

        return false;
    }


    /**
     * RSI must support the direction without being at an
     * extreme exhaustion level.
     * Bullish:
     *     50 <= RSI < 70
     * Bearish:
     *     30 < RSI <= 50
     */
    private boolean hasHealthyRsi(
            TechnicalIndicatorResponse indicators,
            String direction) {

        Double rsi =
                indicators.getRsi14();

        if (rsi == null) {
            return false;
        }

        if ("BULLISH".equals(direction)) {

            return rsi >= 50
                    && rsi < 70;
        }

        if ("BEARISH".equals(direction)) {

            return rsi > 30
                    && rsi <= 50;
        }

        return false;
    }


    /**
     * Discovery strength.
     * We trust the discovery layer more when the stock was
     * already identified as a meaningful trend rather than
     * simply appearing in the universe.
     */
    private boolean hasDiscoveryStrength(
            TrendingStockResponse candidate,
            String direction) {

        String level =
                candidate.getTrendingLevel();

        if (level == null) {
            return false;
        }

        String normalized =
                level.trim().toUpperCase();


        if ("IGNORE".equals(normalized)) {
            return false;
        }

        if ("BULLISH".equals(direction)
                || "BEARISH".equals(direction)) {

            return "STRONG".equals(normalized)
                    || "MODERATE".equals(normalized);
        }

        return false;
    }


    /**
     * Hard reversal threshold.
     * This is intentionally more conservative than the
     * old +/-0.25% classification.
     * A move of 1% or more against the discovery direction
     * is considered a material reversal for this gate.
     */
    private boolean isHardReversal(
            double movementPct) {

        return movementPct <= -1.0;
    }


    private String determineStatus(int score) {

        if (score >= 5) {
            return "QUALIFIED";
        }

        if (score >= 3) {
            return "WEAKENED";
        }

        return "REJECTED";
    }


    private String determineDirection(
            TrendingStockResponse candidate) {

        if (candidate.getTrendDirection() == null) {
            return "NEUTRAL";
        }

        String direction =
                candidate.getTrendDirection()
                        .trim()
                        .toUpperCase();

        if ("BULLISH".equals(direction)) {
            return "BULLISH";
        }

        if ("BEARISH".equals(direction)) {
            return "BEARISH";
        }

        return "NEUTRAL";
    }


    private double calculateDirectionalMovement(
            double discoveryPrice,
            double currentPrice,
            String direction) {

        if ("BULLISH".equals(direction)) {

            return ((currentPrice - discoveryPrice)
                    / discoveryPrice) * 100.0;
        }

        if ("BEARISH".equals(direction)) {

            return ((discoveryPrice - currentPrice)
                    / discoveryPrice) * 100.0;
        }

        return 0.0;
    }


    private String buildReason(
            double movementPct,
            String direction,
            int score,
            String status) {

        return String.format(
                "Direction=%s | MovementPct=%.3f | Score=%d | Status=%s",
                direction,
                movementPct,
                score,
                status
        );
    }


    private MiddayRevalidationResult rejected(
            TrendingStockResponse candidate,
            String reason,
            Double currentPrice) {

        String symbol =
                candidate.getTradingSymbol();

        String direction =
                determineDirection(candidate);

        Double discoveryPrice =
                candidate.getLastPrice();

        return MiddayRevalidationResult.builder()
                .tradingSymbol(symbol)
                .direction(direction)
                .discoveryPrice(discoveryPrice)
                .currentPrice(currentPrice)
                .directionalMovementPct(
                        calculateDirectionalMovementIfPossible(
                                discoveryPrice,
                                currentPrice,
                                direction
                        )
                )
                .score(0)
                .status("REJECTED")
                .reason(reason)
                .build();
    }

    private String determineFailureReason(
            Throwable error) {

        if (error == null) {
            return "REVALIDATION_ERROR";
        }

        String message =
                error.getMessage();

        if (message == null || message.isBlank()) {
            return "REVALIDATION_ERROR";
        }

        String normalized =
                message.toLowerCase();

        if (normalized.contains("timeout")
                || normalized.contains("timed out")) {

            return "MARKET_DATA_TIMEOUT";
        }

        if (normalized.contains("connection")
                || normalized.contains("connect")) {

            return "MARKET_DATA_CONNECTION_ERROR";
        }

        return "REVALIDATION_ERROR";
    }

    private Double calculateDirectionalMovementIfPossible(
            Double discoveryPrice,
            Double currentPrice,
            String direction) {

        if (discoveryPrice == null
                || currentPrice == null
                || discoveryPrice <= 0) {

            return null;
        }

        return calculateDirectionalMovement(
                discoveryPrice,
                currentPrice,
                direction
        );
    }
}