package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.cache.CacheKeyBuilder;
import com.ram.trading.ai.engine.cache.RedisCacheService;
import com.ram.trading.ai.engine.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiCallControlService {

    private static final String KEY_PREFIX =
            "AI_EVALUATION_STATE:";

    private final RedisCacheService redisCacheService;

    @Value("${ai.call-control.index-change-threshold:0.25}")
    private double indexChangeThreshold;

    @Value("${ai.call-control.enabled:true}")
    private boolean enabled;

    @Value("${ai.call-control.min-recheck-seconds:30}")
    private long minRecheckSeconds;

    @Value("${ai.call-control.price-change-percent:0.25}")
    private double priceChangePercent;

    @Value("${ai.call-control.rsi-change:2.0}")
    private double rsiChange;

    @Value("${ai.call-control.macd-change-percent:10.0}")
    private double macdChangePercent;

    @Value("${ai.call-control.state-ttl-hours:8}")
    private long stateTtlHours;

    /**
     * Determines whether an actual LLM call is justified.
     *
     * Returns TRUE when:
     * - call control is disabled
     * - this is the first evaluation
     * - technical direction changed
     * - sufficient time passed AND market changed meaningfully
     *
     * Returns FALSE when:
     * - the same symbol was recently evaluated
     * - there is no meaningful market change
     */



    public boolean shouldCallAi(
            TradingDecisionRequest request,
            AiEvaluationState previousState) {

        if (!enabled) {
            log.debug("AI CALL CONTROL | DISABLED | AI allowed");
            return true;
        }

        if (request == null || request.getSignalRequest() == null) {
            log.warn("AI CALL CONTROL | Invalid request | AI allowed");
            return true;
        }

        SignalGenerationRequest signalRequest = request.getSignalRequest();
        TradingDecision technicalDecision = request.getTechnicalDecision();

        String symbol = signalRequest.getSymbol();

        if (symbol == null || symbol.isBlank()) {
            log.warn("AI CALL CONTROL | Missing symbol | AI allowed");
            return true;
        }

        // First evaluation for this symbol.
        if (previousState == null) {
            log.info(
                    "AI CALL CONTROL | {} | ALLOWED | FIRST_EVALUATION",
                    symbol);
            return true;
        }

        String previousSignal = previousState.getTechnicalSignal();

        String currentSignal =
                technicalDecision != null
                        && technicalDecision.getSignal() != null
                        ? technicalDecision.getSignal().name()
                        : null;

        if (!sameValue(previousSignal, currentSignal)) {
            log.info(
                    "AI CALL CONTROL | {} | ALLOWED | SIGNAL_CHANGED | {} -> {}",
                    symbol,
                    previousSignal,
                    currentSignal);
            return true;
        }

        // Check market context BEFORE cooldown.
        // A meaningful context change must not be suppressed by cooldown.
        if (hasMarketContextChanged(
                signalRequest.getMarketContext(),
                previousState)) {

            log.info(
                    "AI CALL CONTROL | {} | ALLOWED | MARKET_CONTEXT_CHANGED",
                    symbol);
            return true;
        }

        long elapsedMillis =
                System.currentTimeMillis() - previousState.getEvaluatedAt();

        long minimumIntervalMillis = minRecheckSeconds * 1000L;

        // Preserve the existing cooldown behaviour for unchanged context.
        if (elapsedMillis >= 0
                && elapsedMillis < minimumIntervalMillis) {

            log.debug(
                    "AI CALL CONTROL | {} | SKIPPED | COOLDOWN | elapsed={}ms",
                    symbol,
                    elapsedMillis);
            return false;
        }

        // Significant price movement.
        if (hasSignificantPriceChange(
                previousState.getCurrentPrice(),
                signalRequest.getCurrentPrice())) {

            log.info(
                    "AI CALL CONTROL | {} | ALLOWED | PRICE_CHANGED",
                    symbol);
            return true;
        }

        // Significant RSI movement.
        if (hasSignificantRsiChange(
                previousState.getRsi(),
                signalRequest.getRsi())) {

            log.info(
                    "AI CALL CONTROL | {} | ALLOWED | RSI_CHANGED",
                    symbol);
            return true;
        }

        // Significant MACD movement.
        if (hasSignificantMacdChange(
                previousState.getMacd(),
                signalRequest.getMacd())) {

            log.info(
                    "AI CALL CONTROL | {} | ALLOWED | MACD_CHANGED",
                    symbol);
            return true;
        }

        log.debug(
                "AI CALL CONTROL | {} | SKIPPED | NO_MEANINGFUL_CHANGE",
                symbol);

        return false;
    }


    public AiEvaluationState getEvaluationState(
            TradingDecisionRequest request) {

        if (request == null ||
                request.getSignalRequest() == null) {

            log.warn(
                    "AI CALL CONTROL | Invalid request");

            return null;
        }

        String symbol =
                request.getSignalRequest().getSymbol();

        if (symbol == null ||
                symbol.isBlank()) {

            log.warn(
                    "AI CALL CONTROL | Missing symbol");

            return null;
        }

        return redisCacheService.get(
                buildKey(symbol),
                AiEvaluationState.class);
    }

    /**
     * Returns the previously successful AI decision.
     */
    public AiDecisionResponse getPreviousAiResponse(
            TradingDecisionRequest request) {

        if (request == null ||
                request.getSignalRequest() == null) {

            return null;
        }

        String symbol =
                request.getSignalRequest()
                        .getSymbol();

        if (symbol == null ||
                symbol.isBlank()) {

            return null;
        }

        AiEvaluationState state =
                redisCacheService.get(
                        buildKey(symbol),
                        AiEvaluationState.class);

        if (state == null) {

            return null;
        }

        return state.getAiDecisionResponse();
    }

    /**
     * Records the market state and successful AI response
     * AFTER the actual LLM call succeeds.
     */


    public void recordAiEvaluation(
            TradingDecisionRequest request,
            AiDecisionResponse response) {

        if (request == null
                || request.getSignalRequest() == null
                || response == null) {
            return;
        }

        SignalGenerationRequest signalRequest =
                request.getSignalRequest();

        TradingDecision technicalDecision =
                request.getTechnicalDecision();

        MarketContext marketContext =
                signalRequest.getMarketContext();

        String symbol = signalRequest.getSymbol();

        if (symbol == null || symbol.isBlank()) {
            return;
        }

        // Store the exact request key with the evaluation state.
        // If key generation fails, do not trust this state for reuse.
        String cacheKey = null;

        try {
            cacheKey = CacheKeyBuilder.buildAiDecisionKey(request);
        } catch (IllegalArgumentException ex) {
            log.warn(
                    "AI CALL CONTROL | {} | Unable to build evaluation cache key; response reuse must be rejected",
                    symbol,
                    ex);
        }

        AiEvaluationState state = AiEvaluationState.builder()
                .symbol(symbol)
                .cacheKey(cacheKey)

                // Technical indicator snapshot
                .currentPrice(signalRequest.getCurrentPrice())
                .rsi(signalRequest.getRsi())
                .ema20(signalRequest.getEma20())
                .ema50(signalRequest.getEma50())
                .macd(signalRequest.getMacd())
                .signalLine(signalRequest.getSignalLine())
                .volume(signalRequest.getVolume())

                .technicalSignal(
                        technicalDecision != null
                                && technicalDecision.getSignal() != null
                                ? technicalDecision.getSignal().name()
                                : null)

                .confidenceLevel(
                        technicalDecision != null
                                && technicalDecision.getConfidenceLevel() != null
                                ? technicalDecision.getConfidenceLevel().name()
                                : null)

                // Market context snapshot
                .niftyChange(
                        marketContext != null
                                ? marketContext.getNiftyChange()
                                : null)

                .bankNiftyChange(
                        marketContext != null
                                ? marketContext.getBankNiftyChange()
                                : null)

                .volatilityIndex(
                        marketContext != null
                                ? marketContext.getVolatilityIndex()
                                : null)

                .marketTrend(
                        marketContext != null
                                ? marketContext.getMarketTrend()
                                : null)

                .sectorTrend(
                        marketContext != null
                                ? marketContext.getSectorTrend()
                                : null)

                .newsSentiment(
                        marketContext != null
                                ? marketContext.getNewsSentiment()
                                : null)

                .tradingSession(
                        marketContext != null
                                ? marketContext.getTradingSession()
                                : null)

                .averageVolume(
                        marketContext != null
                                ? marketContext.getAverageVolume()
                                : null)

                .averageTrueRange(
                        marketContext != null
                                ? marketContext.getAverageTrueRange()
                                : null)

                .evaluatedAt(System.currentTimeMillis())
                .aiDecisionResponse(response)
                .build();

        redisCacheService.put(
                buildKey(symbol),
                state,
                Duration.ofHours(stateTtlHours));

        log.info(
                "AI CALL CONTROL | {} | STATE_RECORDED | price={} | signal={} | cacheKeyPresent={}",
                symbol,
                signalRequest.getCurrentPrice(),
                state.getTechnicalSignal(),
                cacheKey != null);
    }



    private String buildKey(
            String symbol) {

        return KEY_PREFIX +
                symbol.trim().toUpperCase();
    }

    private boolean hasSignificantPriceChange(
            Double previousPrice,
            Double currentPrice) {

        if (previousPrice == null ||
                currentPrice == null ||
                previousPrice == 0.0) {

            return false;
        }

        double changePercent =
                Math.abs(
                        (currentPrice - previousPrice)
                                / previousPrice)
                        * 100.0;

        return changePercent >=
                priceChangePercent;
    }

    private boolean hasSignificantRsiChange(
            Double previousRsi,
            Double currentRsi) {

        if (previousRsi == null ||
                currentRsi == null) {

            return false;
        }

        return Math.abs(
                currentRsi - previousRsi)
                >= rsiChange;
    }

    private boolean hasSignificantMacdChange(
            Double previousMacd,
            Double currentMacd) {

        if (previousMacd == null ||
                currentMacd == null) {

            return false;
        }

        if (previousMacd == 0.0) {

            return Math.abs(currentMacd)
                    > 0.01;
        }

        double changePercent =
                Math.abs(
                        (currentMacd - previousMacd)
                                / Math.abs(previousMacd))
                        * 100.0;

        return changePercent >=
                macdChangePercent;
    }

    private boolean sameValue(
            String first,
            String second) {

        if (first == null &&
                second == null) {

            return true;
        }

        if (first == null ||
                second == null) {

            return false;
        }

        return first.equalsIgnoreCase(second);
    }

    private boolean hasMarketContextChanged(
            MarketContext current,
            AiEvaluationState previous) {

        if (current == null) {
            // Missing current context must not cause repeated calls.
            return false;
        }

        // A newly available context should be evaluated once.
        if (previous.getMarketTrend() == null
                && previous.getSectorTrend() == null
                && previous.getNewsSentiment() == null
                && previous.getTradingSession() == null
                && previous.getNiftyChange() == null
                && previous.getBankNiftyChange() == null
                && previous.getVolatilityIndex() == null) {

            return current.getMarketTrend() != null
                    || current.getSectorTrend() != null
                    || current.getNewsSentiment() != null
                    || current.getTradingSession() != null
                    || current.getNiftyChange() != null
                    || current.getBankNiftyChange() != null
                    || current.getVolatilityIndex() != null;
        }

        if (!sameValue(
                previous.getMarketTrend() == null
                        ? null : previous.getMarketTrend().name(),
                current.getMarketTrend() == null
                        ? null : current.getMarketTrend().name())) {
            return true;
        }

        if (!sameValue(
                previous.getSectorTrend() == null
                        ? null : previous.getSectorTrend().name(),
                current.getSectorTrend() == null
                        ? null : current.getSectorTrend().name())) {
            return true;
        }

        if (!sameValue(
                previous.getNewsSentiment() == null
                        ? null : previous.getNewsSentiment().name(),
                current.getNewsSentiment() == null
                        ? null : current.getNewsSentiment().name())) {
            return true;
        }

        if (!sameValue(
                previous.getTradingSession() == null
                        ? null : previous.getTradingSession().name(),
                current.getTradingSession() == null
                        ? null : current.getTradingSession().name())) {
            return true;
        }

        return hasSignificantIndexChange(
                previous.getNiftyChange(),
                current.getNiftyChange())
                || hasSignificantIndexChange(
                previous.getBankNiftyChange(),
                current.getBankNiftyChange());
    }

    private boolean hasSignificantIndexChange(
            Double previous,
            Double current) {

        // Avoid treating unavailable data as a market movement.
        if (previous == null || current == null) {
            return false;
        }

        return Math.abs(current - previous)
                >= indexChangeThreshold;
    }

}