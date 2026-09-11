package com.ram.trading.signal.engine.service.impl;

import com.ram.trading.signal.engine.contant.SignalType;

import com.ram.trading.signal.engine.dto.EntryQualityResult;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.portfolio.PortfolioContextResponse;
import com.ram.trading.signal.engine.dto.premarket.JudasSwingResult;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.ORBResult;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.util.TradingSessionService;
import com.ram.trading.signal.engine.exit.TradeExitService;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.MarketContext;
import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import com.ram.trading.signal.engine.risk.RiskEvaluation;
import com.ram.trading.signal.engine.risk.RiskGuardResult;
import com.ram.trading.signal.engine.risk.RiskGuardService;
import com.ram.trading.signal.engine.service.*;
import com.ram.trading.signal.engine.service.ai.TradingFunnelStatisticsService;
import com.ram.trading.signal.engine.service.ai.TradingOrchestratorService;
import com.ram.trading.signal.engine.service.ai.mapper.TradingSignalMapper;
import com.ram.trading.signal.engine.service.context.TradingContext;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.dto.ai.decision.TradingPipelineResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalGenerationServiceImpl implements SignalGenerationService {

    private final MarketDataProvider marketDataProvider;

    private final TradingOrchestratorService tradingOrchestratorService;

    private final TradingSignalMapper tradingSignalMapper;

    private final TradingSignalService tradingSignalService;

    private final TechnicalIndicatorService technicalIndicatorService;

    private final PaperTradingService paperTradingService;

    private final RiskGuardService riskGuardService;

    private final TradeExitService tradeExitService;

    private final OpportunityService opportunityService;

    private final TradingFunnelStatisticsService tradingFunnelStatisticsService;

    private final EntryQualityService entryQualityService;

    private final MinuteCandleAggregator minuteCandleAggregator;

    private final TradingSessionService tradingSessionService;

    private final OpeningRangeService openingRangeService;

    private final JudasSwingDetector judasSwingDetector;

    private final ORBValidator orbValidator;

    private final Map<String, String> processedStrategySetups = new ConcurrentHashMap<>();

    private record EntrySetup(
            String strategy,
            String direction,
            LocalDateTime confirmationTime
    ) {}

    @Override
    public Mono<TradingSignal> generateSignal(String symbol) {

        log.info("========== SIGNAL GENERATION STARTED ==========");
        log.info("Symbol : {}", symbol);

        /*
         * ============================================================
         * IMPORTANT SAFETY GATE
         * ============================================================
         *
         * This String-based signal generation path is used by the
         * Market Scanner / revalidation flow.
         *
         * It does NOT have a JUDAS / ORB EntrySetup.
         *
         * Therefore it MUST NOT create a trading signal that can
         * reach PaperTradingService and open a trade.
         *
         * All automatic entries must come through:
         *
         * Tick
         *   ↓
         * Opening Range
         *   ↓
         * JUDAS / ORB
         *   ↓
         * EntrySetup
         *   ↓
         * Engineering
         *   ↓
         * AI
         *   ↓
         * Risk Guard
         *   ↓
         * Trade
         *
         * The Tick-based generateSignal(Tick) method is responsible
         * for the actual strategy-entry pipeline.
         * ============================================================
         */

        if (!tradingSessionService.canCreateStrategyEntry()) {

            log.debug(
                    "GENERIC SIGNAL PATH BLOCKED | " +
                            "Symbol={} | Strategy entry window is closed",
                    symbol);

            return Mono.just(
                    TradingSignal.builder()
                            .symbol(symbol)
                            .signal(SignalType.HOLD.name())
                            .build());
        }

        /*
         * ============================================================
         * GENERIC SCANNER PATH
         * ============================================================
         *
         * We can still calculate technical indicators for analysis,
         * but we deliberately DO NOT call generateTradingSignal().
         *
         * Calling generateTradingSignal() would eventually execute
         * postProcessSignal(), which can create a paper trade.
         *
         * Therefore this path remains analysis-only.
         * ============================================================
         */

        return marketDataProvider
                .getStockPrice(symbol)

                .doOnNext(stock ->
                        log.debug(
                                "Scanner Analysis Price | Symbol={} | Price={}",
                                stock.getSymbol(),
                                stock.getPrice()))

                .zipWhen(stock ->
                        technicalIndicatorService
                                .calculate(symbol)
                                .switchIfEmpty(
                                        Mono.defer(() -> {

                                            log.warn(
                                                    "Scanner Analysis skipped | " +
                                                            "Technical indicators unavailable | " +
                                                            "Symbol={}",
                                                    symbol);

                                            return Mono.empty();
                                        }))
                )

                .map(tuple -> {

                    StockResponse stock = tuple.getT1();
                    TechnicalIndicatorResponse indicator = tuple.getT2();

                    log.debug(
                            "Scanner Analysis Completed | " +
                                    "Symbol={} | RSI={} | EMA20={} | EMA50={} | MACD={}",
                            symbol,
                            indicator.getRsi14(),
                            indicator.getEma20(),
                            indicator.getEma50(),
                            indicator.getMacd());

                    /*
                     * IMPORTANT:
                     *
                     * Do NOT call:
                     *
                     * generateTradingSignal(request, indicator)
                     *
                     * here.
                     *
                     * This method has no JUDAS / ORB EntrySetup and
                     * therefore must never produce an executable BUY
                     * or SELL signal.
                     */

                    return TradingSignal.builder()
                            .symbol(stock.getSymbol())
                            .signal(SignalType.HOLD.name())
                            .build();
                })

                .doOnSuccess(signal -> {

                    if (signal != null) {

                        log.debug(
                                "Scanner Analysis Completed | " +
                                        "Symbol={} | Result={}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.debug(
                            "========== GENERIC SIGNAL ANALYSIS COMPLETED ==========");
                })

                .doOnError(error ->
                        log.error(
                                "Scanner analysis failed for {}",
                                symbol,
                                error));
    }

    @Override
    public Mono<TradingSignal> generateSignal(Tick tick) {

        log.info(
                "========== LIVE SIGNAL GENERATION STARTED ==========");

        if (tick == null) {

            log.warn(
                    "Live Signal Generation skipped | Tick is null");

            return Mono.empty();
        }

        if (!tradingSessionService.isMarketOpen()) {

            log.debug(
                    "LIVE SIGNAL GATE | Symbol={} | Market session closed | Skipping signal pipeline",
                    tick.getSymbol());

            return Mono.empty();
        }

        log.debug(
                "Symbol : {}",
                tick.getSymbol());

        log.debug(
                "LTP    : {}",
                tick.getLastTradedPrice());

        /*
         * ============================================================
         * LIVE 1-MINUTE CANDLE AGGREGATION
         * ============================================================
         *
         * Every incoming live tick is first passed to the
         * MinuteCandleAggregator.
         *
         * This builds the 1-minute OHLC + volume candles that
         * will later be used by:
         *
         *     OpeningRangeService
         *     JudasSwingDetector
         *     ORBValidator
         *
         * IMPORTANT:
         *
         * Candle aggregation does NOT make a trading decision.
         * It only maintains market structure data.
         */

        MinuteCandle completedCandle = null;

        try {

            completedCandle =
                    minuteCandleAggregator.process(tick);

            if (completedCandle != null) {

                log.debug(
                        "1-MIN CANDLE COMPLETED | Symbol={} | Minute={} | O={} | H={} | L={} | C={} | V={}",
                        completedCandle.getSymbol(),
                        completedCandle.getMinute(),
                        completedCandle.getOpen(),
                        completedCandle.getHigh(),
                        completedCandle.getLow(),
                        completedCandle.getClose(),
                        completedCandle.getVolume()
                );
            }

        } catch (Exception ex) {

            /*
             * Candle aggregation must NEVER break
             * the existing signal-generation pipeline.
             */
            log.error(
                    "1-MIN CANDLE AGGREGATION FAILED | " +
                            "Symbol={} | Continuing signal pipeline",
                    tick.getSymbol(),
                    ex);
        }

        final MinuteCandle finalCompletedCandle =
                completedCandle;

        return tradeExitService
                .evaluateExit(tick)
                .then(
                        Mono.defer(() -> {

                            if (!tradingSessionService.canCreateTrade()) {

                                log.debug(
                                        "ENTRY SESSION CLOSED | Symbol={} | Entry cutoff reached | Skipping setup/AI",
                                        tick.getSymbol());

                                return Mono.empty();
                            }

                            /*
                             * ====================================================
                             * ENTRY STRATEGY GATE
                             * ====================================================
                             *
                             * Entry strategies are evaluated ONLY when a
                             * completed 1-minute candle is available.
                             *
                             * Trade exits remain evaluated on EVERY tick.
                             */

                            if (finalCompletedCandle == null) {

                                log.debug(
                                        "ENTRY STRATEGY GATE | {} | " +
                                                "No completed candle | " +
                                                "Skipping entry evaluation",
                                        tick.getSymbol());

                                return Mono.empty();
                            }

                            EntrySetup entrySetup =
                                    findEntrySetup(
                                            finalCompletedCandle);

                            if (entrySetup == null) {

                                log.debug(
                                        "ENTRY STRATEGY GATE | {} | " +
                                                "No valid Judas/ORB setup | " +
                                                "Skipping AI",
                                        tick.getSymbol());

                                return Mono.empty();
                            }

                            log.info(
                                    "ENTRY STRATEGY GATE PASSED | " +
                                            "Symbol={} | Strategy={} | Direction={} | Confirmation={}",
                                    tick.getSymbol(),
                                    entrySetup.strategy(),
                                    entrySetup.direction(),
                                    entrySetup.confirmationTime());

                            recordStrategySetup(entrySetup);

                            log.info(
                                    "ENTRY STRATEGY GATE PASSED | " +
                                            "Symbol={} | Candle={}",
                                    tick.getSymbol(),
                                    finalCompletedCandle.getMinute());

                            return technicalIndicatorService
                                    .calculate(
                                            tick.getSymbol())

                                    .switchIfEmpty(
                                            Mono.defer(() -> {

                                                log.warn(
                                                        "Skipping {} because technical indicators are unavailable.",
                                                        tick.getSymbol());

                                                return Mono.empty();
                                            })
                                    )

                                    .flatMap(indicator -> {

                                        log.debug(
                                                "Technical Indicators Loaded");

                                        log.debug(
                                                "RSI      : {}",
                                                indicator.getRsi14());

                                        log.debug(
                                                "EMA20    : {}",
                                                indicator.getEma20());

                                        log.debug(
                                                "EMA50    : {}",
                                                indicator.getEma50());

                                        log.debug(
                                                "SMA20    : {}",
                                                indicator.getSma20());

                                        log.debug(
                                                "SMA50    : {}",
                                                indicator.getSma50());

                                        log.debug(
                                                "MACD     : {}",
                                                indicator.getMacd());

                                        SignalGenerationRequest request =
                                                buildSignalRequest(
                                                        tick,
                                                        indicator,
                                                        entrySetup);

                                        return generateTradingSignal(
                                                request,
                                                indicator);
                                    });
                        })
                );
    }

    private Mono<TradingSignal> generateTradingSignal(
            SignalGenerationRequest request,
            TechnicalIndicatorResponse indicator) {

        log.debug(
                "======================================================");

        log.debug(
                "Trading Decision Pipeline Started");

        log.debug(
                "Symbol : {}",
                request.getSymbol());

        log.debug(
                "======================================================");

        return tradingOrchestratorService
                .executeTrade(request)

                .flatMap(pipelineResult -> {

                    log.debug(
                            "******** TRADING PIPELINE RESULT RECEIVED ********");

                    /*
                     * =====================================================
                     * PIPELINE RESULT VALIDATION
                     * =====================================================
                     */

                    if (pipelineResult == null) {

                        log.warn(
                                "Trading pipeline result is null for {}",
                                request.getSymbol());

                        return Mono.empty();
                    }

                    /*
                     * =====================================================
                     * AI DECISION VALIDATION
                     * =====================================================
                     */

                    if (pipelineResult.getAiDecision() == null) {

                        log.info(
                                "No AI decision generated for {}. Reason: {}",
                                request.getSymbol(),
                                pipelineResult.getSkipReason());

                        return Mono.empty();
                    }

                    /*
                     * =====================================================
                     * GET AI RESPONSE
                     * =====================================================
                     */

                    AiDecisionResponse aiResponse =
                            pipelineResult.getAiDecision();

                    log.debug(
                            "******** AI RESPONSE RECEIVED ********");

                    if (aiResponse.getDecision() == null) {

                        log.warn(
                                "AI decision is null for {}",
                                request.getSymbol());

                        return Mono.empty();
                    }

                    /*
                     * =====================================================
                     * FUNNEL : AI DECISION
                     * =====================================================
                     */

                    String recommendation =
                            aiResponse
                                    .getDecision()
                                    .getRecommendation()
                                    .name();

                    Boolean tradeAllowed =
                            aiResponse
                                    .getDecision()
                                    .getTradeAllowed();

                    if (SignalType.BUY.name()
                            .equalsIgnoreCase(recommendation)) {

                        tradingFunnelStatisticsService
                                .recordAiBuy();

                    } else if (SignalType.SELL.name()
                            .equalsIgnoreCase(recommendation)) {

                        tradingFunnelStatisticsService
                                .recordAiSell();

                    } else {

                        tradingFunnelStatisticsService
                                .recordAiHold();
                    }

                    if (!Boolean.TRUE.equals(tradeAllowed)) {

                        tradingFunnelStatisticsService
                                .recordAiTradeNotAllowed();
                    }

                    log.debug(
                            "Trade Allowed : {}",
                            tradeAllowed);

                    log.debug(
                            "Recommendation : {}",
                            recommendation);

                    logAiDecision(aiResponse);

                    /*
                     * =====================================================
                     * AI TRADE-ALLOWED GUARD
                     * =====================================================
                     */

                    if (!Boolean.TRUE.equals(tradeAllowed)) {

                        log.info(
                                "AI Trade Not Allowed. Returning HOLD | Symbol={} | Recommendation={}",
                                request.getSymbol(),
                                recommendation);

                        return Mono.just(
                                TradingSignal.builder()
                                        .symbol(request.getSymbol())
                                        .signal(SignalType.HOLD.name())
                                        .build());
                    }

                    /*
                     * =====================================================
                     * ENGINEERING ↔ AI DIRECTION GUARD
                     * =====================================================
                     *
                     * BUY  + BUY  -> continue
                     * SELL + SELL -> continue
                     * BUY  + SELL -> HOLD
                     * SELL + BUY  -> HOLD
                     */

                    TradingSignal directionGuardResult =
                            validateAiDirection(
                                    pipelineResult,
                                    aiResponse,
                                    request);

                    if (directionGuardResult != null) {
                        return Mono.just(directionGuardResult);
                    }

                    /*
                     * =====================================================
                     * GET EXISTING TRADING CONTEXT
                     * =====================================================
                     */

                    TradingContext context =
                            pipelineResult.getTradingContext();

                    if (context == null) {

                        log.error(
                                "TradingContext is missing for eligible AI decision. Symbol : {}",
                                request.getSymbol());

                        return Mono.empty();
                    }

                    logTradingContext(context);

                    /*
                     * =====================================================
                     * RISK GUARD
                     * =====================================================
                     */

                    RiskEvaluation evaluation =
                            RiskEvaluation.builder()
                                    .context(context)
                                    .decision(
                                            aiResponse.getDecision())
                                    .aiResponse(aiResponse)
                                    .signal(
                                            TradingSignal.builder()
                                                    .symbol(
                                                            request.getSymbol())
                                                    .build())
                                    .build();

                    try {

                        log.debug(
                                "STEP-1 Before Risk Guard");

                        /*
                         * =================================================
                         * FUNNEL : RISK EVALUATED
                         * =================================================
                         */

                        tradingFunnelStatisticsService
                                .recordRiskEvaluated();

                        RiskGuardResult result =
                                riskGuardService.evaluate(
                                        evaluation);

                        log.debug(
                                "STEP-2 Risk Guard Completed");

                        logRiskEvaluation(result);

                        /*
                         * =================================================
                         * FUNNEL : RISK RESULT
                         * =================================================
                         */

                        if (result.isApproved()) {

                            tradingFunnelStatisticsService
                                    .recordRiskApproved();

                        } else {

                            tradingFunnelStatisticsService
                                    .recordRiskRejected();
                        }

                        /*
                         * =================================================
                         * MAP AI RESPONSE TO TRADING SIGNAL
                         * =================================================
                         */

                        log.debug(
                                "STEP-3 Before Mapper");

                        TradingSignal signal =
                                tradingSignalMapper.map(
                                        aiResponse,
                                        request);

                        log.debug(
                                "STEP-4 Mapper Completed");

                        /*
                         * =================================================
                         * RISK REJECTED
                         * =================================================
                         */

                        if (!result.isApproved()) {

                            signal.setSignal(
                                    SignalType.HOLD.name());

                            log.warn(
                                    "Trade rejected by Risk Guard. Returning HOLD.");

                            return Mono.just(signal);
                        }

                        /*
                         * =================================================
                         * POST PROCESSING
                         * =================================================
                         */

                        tradingFunnelStatisticsService
                                .recordPostProcessingEntered();

                        /*
                         * IMPORTANT:
                         *
                         * Pass the original market price from the
                         * SignalGenerationRequest into post processing.
                         *
                         * This becomes our reference price for the
                         * Entry Quality measurement.
                         */

                        return postProcessSignal(
                                signal,
                                indicator,
                                request.getCurrentPrice());

                    } catch (Exception ex) {

                        log.error(
                                "FAILED INSIDE generateTradingSignal()",
                                ex);

                        return Mono.error(ex);
                    }
                })

                .doOnSuccess(signal -> {

                    if (signal != null) {

                        log.info(
                                "Final Decision : {} {}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.debug(
                            "Trading Decision Pipeline Completed");
                })

                .doOnError(error ->
                        log.error(
                                "Trading Decision Pipeline Failed",
                                error));
    }

    /**
     * Validates that the AI recommendation confirms the technical
     * direction generated by the engineering pipeline.
     *
     * AI acts as a confirmation / veto layer.
     *
     * BUY  + BUY  -> CONTINUE
     * SELL + SELL  -> CONTINUE
     *
     * BUY  + SELL  -> HOLD
     * SELL + BUY   -> HOLD
     *
     * BUY  + HOLD  -> HOLD
     * SELL + HOLD  -> HOLD
     *
     * HOLD + BUY   -> HOLD
     * HOLD + SELL  -> HOLD
     * HOLD + HOLD  -> HOLD
     *
     * Any invalid / unknown direction -> HOLD
     *
     * @return HOLD signal when the AI does not confirm the engineering
     *         direction; otherwise null so the normal pipeline continues.
     */
    private TradingSignal validateAiDirection(
            TradingPipelineResult pipelineResult,
            AiDecisionResponse aiResponse,
            SignalGenerationRequest request) {

        /*
         * ============================================================
         * VALIDATION
         * ============================================================
         */

        if (pipelineResult == null
                || pipelineResult.getTechnicalDecision() == null
                || aiResponse == null
                || aiResponse.getDecision() == null
                || aiResponse.getDecision().getRecommendation() == null) {

            log.warn(
                    "AI Direction Guard -> HOLD | Incomplete decision data | Symbol={}",
                    request.getSymbol());

            return TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(SignalType.HOLD.name())
                    .build();
        }

        /*
         * ============================================================
         * ENGINEERING / TECHNICAL SIGNAL
         * ============================================================
         */

        SignalType technicalSignal =
                pipelineResult
                        .getTechnicalDecision()
                        .getSignal();

        /*
         * ============================================================
         * AI SIGNAL
         * ============================================================
         */

        SignalType aiSignal;

        try {

            aiSignal = SignalType.valueOf(
                    aiResponse
                            .getDecision()
                            .getRecommendation()
                            .name()
                            .toUpperCase());

        } catch (IllegalArgumentException ex) {

            log.warn(
                    "AI Direction Guard -> HOLD | Unknown AI recommendation | Symbol={} | Recommendation={}",
                    request.getSymbol(),
                    aiResponse.getDecision().getRecommendation());

            return TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(SignalType.HOLD.name())
                    .build();
        }

        /*
         * ============================================================
         * ENGINEERING HOLD
         * ============================================================
         *
         * Engineering is the primary direction anchor.
         *
         * If Engineering itself says HOLD, no trade should proceed.
         */

        if (technicalSignal == null
                || SignalType.HOLD.equals(technicalSignal)) {

            log.debug(
                    "AI Direction Guard -> HOLD | Engineering signal is HOLD | Symbol={} | AI={}",
                    request.getSymbol(),
                    aiSignal);

            return TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(SignalType.HOLD.name())
                    .build();
        }

        /*
         * ============================================================
         * AI HOLD
         * ============================================================
         *
         * AI did not confirm the Engineering direction.
         * Therefore, do not trade.
         */

        if (SignalType.HOLD.equals(aiSignal)) {

            log.debug(
                    "AI Direction Guard -> HOLD | AI did not confirm Engineering direction | Symbol={} | Engineering={}",
                    request.getSymbol(),
                    technicalSignal);

            return TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(SignalType.HOLD.name())
                    .build();
        }

        /*
         * ============================================================
         * DIRECTION MATCH
         * ============================================================
         *
         * Engineering BUY  + AI BUY  -> Continue
         * Engineering SELL + AI SELL -> Continue
         */

        if (technicalSignal == aiSignal) {

            tradingFunnelStatisticsService.recordAiDirectionMatch();

            log.debug(
                    "AI DIRECTION CONFIRMED | Symbol={} | Direction={}",
                    request.getSymbol(),
                    technicalSignal);

            return null;
        }

        /*
         * ============================================================
         * DIRECTION MISMATCH
         * ============================================================
         *
         * Engineering BUY  + AI SELL -> HOLD
         * Engineering SELL + AI BUY  -> HOLD
         *
         * AI is NOT allowed to reverse Engineering direction.
         */

        tradingFunnelStatisticsService.recordAiDirectionMismatch();

        log.warn(
                "AI DIRECTION MISMATCH -> HOLD | Symbol={} | Engineering={} | AI={}",
                request.getSymbol(),
                technicalSignal,
                aiSignal);

        return TradingSignal.builder()
                .symbol(request.getSymbol())
                .signal(SignalType.HOLD.name())
                .build();
    }

    private void logTradingContext(TradingContext context) {

        log.debug("========== Trading Context ==========");

        log.debug("News      : {}", context.getNewsSummary());
        log.debug("Sector    : {}", context.getSectorSummary());

        PortfolioContextResponse portfolio =
                context.getPortfolioContext();

        if (portfolio != null) {

            if (portfolio.getSummary() != null) {
                log.debug("Portfolio Summary : {}",
                        portfolio.getSummary());
            }

            if (portfolio.getRecommendations() != null) {
                log.debug("Portfolio Recommendations : {}",
                        portfolio.getRecommendations());
            }

            if (portfolio.getRisk() != null) {
                log.debug("Portfolio Risk : {}",
                        portfolio.getRisk().getRiskLevel());
            }

            if (portfolio.getHealth() != null) {
                log.debug("Portfolio Health : {}",
                        portfolio.getHealth().getStatus());
            }

        } else {

            log.debug("Portfolio Context : Not Available");
        }

        log.debug("News Score : {}",
                context.getNewsScore());

        log.debug("News Sentiment : {}",
                context.getNewsSentiment());

        if (context.getOpenPositionContext() != null) {

            log.debug("Open Position Exists : {}",
                    context.getOpenPositionContext().isPositionExists());

            if (context.getOpenPositionContext().isPositionExists()) {

                log.debug("Open Position Signal : {}",
                        context.getOpenPositionContext().getSignal());

                log.debug("Current PnL : {}",
                        context.getOpenPositionContext().getCurrentPnL());
            }

        } else {

            log.debug("Open Position Context : Not Available");
        }

        log.debug("====================================");
    }

    private void logAiDecision(AiDecisionResponse aiResponse) {

        log.debug("========== AI RESPONSE ==========");

        if (aiResponse.getDecision() != null) {

            log.debug("Trade Allowed      : {}",
                    aiResponse.getDecision().getTradeAllowed());

            log.debug("Recommendation     : {}",
                    aiResponse.getDecision().getRecommendation());

            log.debug("Confidence         : {}",
                    aiResponse.getDecision().getConfidence());

            log.debug("Decision Strength  : {}",
                    aiResponse.getDecision().getDecisionStrength());

        } else {

            log.warn("Decision : NULL");
        }

        log.debug("AI Reasoning       : {}",
                aiResponse.getAiReasoning());

        log.debug("Technical Analysis : {}",
                aiResponse.getTechnicalAnalysis());

        log.debug("Risk Analysis      : {}",
                aiResponse.getRiskAnalysis());

        log.debug("News Analysis      : {}",
                aiResponse.getNewsAnalysis());

        log.debug("Portfolio Analysis : {}",
                aiResponse.getPortfolioAnalysis());

        log.debug("Execution Plan     : {}",
                aiResponse.getExecutionPlan());

        log.debug("=================================");
    }

    private void logRiskEvaluation(
            RiskGuardResult result) {

        log.debug("========== RISK GUARD ==========");

        log.debug("Approved : {}",
                result.isApproved());

        if (!result.isApproved()) {

            result.getViolations()
                    .forEach(v ->
                            log.warn("{} -> {}",
                                    v.getRule(),
                                    v.getReason()));
        }

        log.debug("================================");
    }

    private Mono<TradingSignal> postProcessSignal(
            TradingSignal signal,
            TechnicalIndicatorResponse indicator,
            Double signalPrice) {

        log.info("========== POST PROCESSING ==========");

        if (SignalType.HOLD.name().equals(
                signal.getSignal())) {

            log.info(
                    "Signal is HOLD. Nothing to persist.");

            return Mono.just(signal);
        }


        /*
         * ============================================================
         * ENTRY QUALITY
         *
         * Measurement ONLY.
         *
         * IMPORTANT:
         * No trade is rejected in this phase.
         * ============================================================
         */

        return marketDataProvider
                .getStockPrice(signal.getSymbol())

                .switchIfEmpty(
                        Mono.defer(() -> {

                            log.warn(
                                    "ENTRY QUALITY | " +
                                            "Fresh market price unavailable | " +
                                            "Symbol={} | " +
                                            "Direction={} | " +
                                            "SignalPrice={}",
                                    signal.getSymbol(),
                                    signal.getSignal(),
                                    signalPrice);

                            return Mono.just(
                                    StockResponse.builder()
                                            .symbol(
                                                    signal.getSymbol())
                                            .price(
                                                    signalPrice != null
                                                            ? signalPrice
                                                            : 0.0)
                                            .build());
                        }))

                .flatMap(stock -> {

                    double freshMarketPrice =
                            stock.getPrice();


                    /*
                     * ====================================================
                     * ENTRY QUALITY SERVICE
                     * ====================================================
                     */

                    EntryQualityResult entryQuality =
                            entryQualityService.evaluate(
                                    signal.getSymbol(),
                                    SignalType.valueOf(
                                            signal.getSignal()
                                                    .toUpperCase()),
                                    signalPrice,
                                    freshMarketPrice,
                                    indicator);


                    /*
                     * ====================================================
                     * IMPORTANT
                     *
                     * We log the classification but DO NOT reject
                     * the trade yet.
                     * ====================================================
                     */

                    log.info(
                            "ENTRY QUALITY RESULT | " +
                                    "Symbol={} | " +
                                    "Status={} | " +
                                    "DirectionalMove={}%" +
                                    " | PriceVsEMA20={}%" +
                                    " | PriceVsEMA50={}%" +
                                    " | PriceVsHistoricalClose={}%",
                            entryQuality.getSymbol(),
                            entryQuality.getStatus(),
                            entryQuality.getDirectionalMovementPct(),
                            entryQuality.getPriceVsEma20Pct(),
                            entryQuality.getPriceVsEma50Pct(),
                            entryQuality.getPriceVsHistoricalClosePct());


                    /*
                     * ====================================================
                     * EXISTING PERSISTENCE FLOW
                     * ====================================================
                     */

                    log.info("Saving Trading Signal...");

                    return Mono.fromCallable(
                                    () ->
                                            tradingSignalService
                                                    .save(signal))

                            .subscribeOn(
                                    Schedulers.boundedElastic())

                            .flatMap(entity -> {

                                tradingFunnelStatisticsService
                                        .recordSignalSaved();

                                log.info(
                                        "Trading Signal Saved : {}",
                                        entity.getId());


                                return Mono.fromRunnable(() -> {

                                            log.info(
                                                    "Saving Opportunity...");

                                            opportunityService.save(
                                                    signal,
                                                    entity.getId());


                                            tradingFunnelStatisticsService
                                                    .recordOpportunitySaved();

                                            log.info(
                                                    "Opportunity Saved Successfully.");

                                        })

                                        .subscribeOn(
                                                Schedulers.boundedElastic())

                                        .thenReturn(entity);
                            })


                            /*
                             * =================================================
                             * PAPER TRADE
                             * =================================================
                             */

                            .flatMap(entity ->

                                    Mono.fromRunnable(() -> {

                                                log.info(
                                                        "Creating Paper Trade...");

                                                tradingFunnelStatisticsService
                                                        .recordPaperTradeAttempted();

                                                paperTradingService.createTrade(
                                                        entity,
                                                        indicator);

                                            })

                                            .subscribeOn(
                                                    Schedulers.boundedElastic())

                                            .thenReturn(signal)
                            );
                })

                .doOnSuccess(
                        s ->
                                log.info(
                                        "Post Processing Completed Successfully."))

                .doOnError(
                        ex ->
                                log.error(
                                        "Post Processing Failed",
                                        ex));
    }


    private SignalGenerationRequest buildSignalRequest(
            StockResponse stock,
            TechnicalIndicatorResponse indicator) {

        return SignalGenerationRequest.builder()
                .symbol(stock.getSymbol())
                .currentPrice(stock.getPrice())
                .rsi(indicator.getRsi14())
                .ema20(indicator.getEma20())
                .ema50(indicator.getEma50())
                .sma20(indicator.getSma20())
                .sma50(indicator.getSma50())
                .macd(indicator.getMacd())
                .signalLine(indicator.getSignalLine())
                .previousMacd(indicator.getPreviousMacd())
                .previousSignalLine(indicator.getPreviousSignalLine())
                .build();
    }

    private EntrySetup findEntrySetup(
            MinuteCandle completedCandle) {

        if (completedCandle == null
                || completedCandle.getSymbol() == null
                || completedCandle.getMinute() == null) {

            return null;
        }

        String symbol =
                completedCandle.getSymbol();

        LocalTime candleTime =
                completedCandle.getMinute().toLocalTime();

        /*
         * ============================================================
         * OPENING RANGE BUILDING
         * ============================================================
         *
         * 09:15 - 09:29
         *
         * No entry strategy is evaluated here.
         * These candles are used to build the Opening Range.
         */
        if (candleTime.isBefore(LocalTime.of(9, 30))) {

            log.debug(
                    "ENTRY STRATEGY GATE | {} | " +
                            "Opening range building | Candle={}",
                    symbol,
                    completedCandle.getMinute());

            return null;
        }

        /*
         * ============================================================
         * GET COMPLETED OPENING RANGE
         * ============================================================
         */

        OpeningRange openingRange =
                openingRangeService.get(symbol);

        if (openingRange == null) {

            openingRange =
                    openingRangeService.calculate(symbol);
        }

        if (openingRange == null
                || !openingRange.isComplete()) {

            log.info(
                    "ENTRY STRATEGY GATE | {} | " +
                            "Opening range unavailable/incomplete",
                    symbol);

            return null;
        }

        /*
         * ============================================================
         * JUDAS
         * ============================================================
         *
         * 09:30 - 10:30
         */
        if (!candleTime.isBefore(LocalTime.of(9, 30))
                && candleTime.isBefore(LocalTime.of(10, 30))) {

            JudasSwingResult judas =
                    judasSwingDetector.detect(openingRange);

            if (judas != null
                    && judas.isDetected()) {

                String setupKey =
                        symbol
                                + "|JUDAS|"
                                + String.valueOf(
                                judas.getConfirmationTime());

                if (processedStrategySetups.putIfAbsent(
                        setupKey,
                        setupKey) == null) {

                    String direction =
                            normalizeStrategyDirection(
                                    judas.getDirection());

                    log.info(
                            "ENTRY STRATEGY GATE | JUDAS VALID | " +
                                    "Symbol={} | Direction={} | " +
                                    "SweepSide={} | Confirmation={}",
                            symbol,
                            direction,
                            judas.getSweepSide(),
                            judas.getConfirmationTime());

                    return new EntrySetup(
                            "JUDAS",
                            direction,
                            judas.getConfirmationTime());
                }

                log.debug(
                        "ENTRY STRATEGY GATE | " +
                                "JUDAS ALREADY PROCESSED | " +
                                "Symbol={} | Confirmation={}",
                        symbol,
                        judas.getConfirmationTime());
            }
        }

        /*
         * ============================================================
         * ORB
         * ============================================================
         *
         * 09:30 - 11:30
         */
        if (!candleTime.isBefore(LocalTime.of(9, 30))
                && candleTime.isBefore(LocalTime.of(11, 30))) {

            ORBResult orb =
                    orbValidator.validate(openingRange);

            if (orb != null
                    && orb.isValid()) {

                String setupKey =
                        symbol
                                + "|ORB|"
                                + String.valueOf(
                                orb.getConfirmationTime());

                if (processedStrategySetups.putIfAbsent(
                        setupKey,
                        setupKey) == null) {

                    String direction =
                            normalizeStrategyDirection(
                                    orb.getDirection());

                    log.info(
                            "ENTRY STRATEGY GATE | ORB VALID | " +
                                    "Symbol={} | Direction={} | " +
                                    "BreakoutSide={} | Confirmation={}",
                            symbol,
                            direction,
                            orb.getBreakoutSide(),
                            orb.getConfirmationTime());

                    return new EntrySetup(
                            "ORB",
                            direction,
                            orb.getConfirmationTime());
                }

                log.debug(
                        "ENTRY STRATEGY GATE | " +
                                "ORB ALREADY PROCESSED | " +
                                "Symbol={} | Confirmation={}",
                        symbol,
                        orb.getConfirmationTime());
            }
        }

        log.debug(
                "ENTRY STRATEGY GATE | NO VALID SETUP | " +
                        "Symbol={} | Candle={}",
                symbol,
                completedCandle.getMinute());

        return null;
    }

    private void recordStrategySetup(EntrySetup entrySetup) {

        if (entrySetup == null || entrySetup.direction() == null) {
            return;
        }

        String strategy = entrySetup.strategy();
        String direction = entrySetup.direction().toUpperCase();

        if ("JUDAS".equalsIgnoreCase(strategy)) {
            if ("BUY".equals(direction)) {
                tradingFunnelStatisticsService.recordJudasBuy();
            } else if ("SELL".equals(direction)) {
                tradingFunnelStatisticsService.recordJudasSell();
            }
        } else if ("ORB".equalsIgnoreCase(strategy)) {
            if ("BUY".equals(direction)) {
                tradingFunnelStatisticsService.recordOrbBuy();
            } else if ("SELL".equals(direction)) {
                tradingFunnelStatisticsService.recordOrbSell();
            }
        }
    }

    private String normalizeStrategyDirection(
            String direction) {

        if (direction == null) {
            return "NONE";
        }

        return switch (direction.toUpperCase()) {

            case "BULLISH",
                 "BULLISH_REVERSAL" ->
                    "BUY";

            case "BEARISH",
                 "BEARISH_REVERSAL" ->
                    "SELL";

            default ->
                    "NONE";
        };
    }

    private SignalGenerationRequest buildSignalRequest(
            Tick tick,
            TechnicalIndicatorResponse indicator,
            EntrySetup entrySetup) {

        log.debug(
                "ENTRY QUALITY CONTEXT | Symbol={} | Strategy={} | Direction={} | EntryPrice={} | CandleTime={} | NIFTY={} | BANKNIFTY={} | Regime={}",
                tick.getSymbol(),
                entrySetup.strategy(),
                entrySetup.direction(),
                tick.getLastTradedPrice(),
                tick.getTradeTime(),
                tick.getNiftyChange(),
                tick.getBankNiftyChange(),
                tick.getMarketRegime());

        return SignalGenerationRequest.builder()
                .symbol(tick.getSymbol())
                .currentPrice(tick.getLastTradedPrice())
                .rsi(indicator.getRsi14())
                .ema20(indicator.getEma20())
                .ema50(indicator.getEma50())
                .sma20(indicator.getSma20())
                .sma50(indicator.getSma50())
                .macd(indicator.getMacd())
                .signalLine(indicator.getSignalLine())
                .previousMacd(indicator.getPreviousMacd())
                .previousSignalLine(indicator.getPreviousSignalLine())

                // Original strategy setup
                .setupStrategy(entrySetup.strategy())
                .setupDirection(
                        SignalType.valueOf(
                                entrySetup.direction()
                                        .toUpperCase()))
                .marketContext(buildMarketContext(tick))
                .build();
    }

    private MarketContext buildMarketContext(Tick tick) {
        if (tick == null) {
            return null;
        }

        return MarketContext.builder()
                .niftyChange(tick.getNiftyChange())
                .bankNiftyChange(tick.getBankNiftyChange())
                .marketTrend(toTrend(tick.getMarketRegime()))
                .marketTime(tick.getTimestamp() != null ? tick.getTradeTime() : null)
                .build();
    }

    private Trend toTrend(String regime) {
        if (regime == null) return null;
        return switch (regime.toUpperCase()) {
            case "BULLISH" -> Trend.BULLISH;
            case "BEARISH" -> Trend.BEARISH;
            case "SIDEWAYS" -> Trend.SIDEWAYS;
            default -> null;
        };
    }

}