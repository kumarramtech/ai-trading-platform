package com.ram.trading.signal.engine.service.impl;

import com.ram.trading.signal.engine.contant.SignalType;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.portfolio.PortfolioContextResponse;
import com.ram.trading.signal.engine.exit.TradeExitService;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import com.ram.trading.signal.engine.risk.RiskEvaluation;
import com.ram.trading.signal.engine.risk.RiskGuardResult;
import com.ram.trading.signal.engine.risk.RiskGuardService;
import com.ram.trading.signal.engine.service.OpportunityService;
import com.ram.trading.signal.engine.service.PaperTradingService;
import com.ram.trading.signal.engine.service.SignalGenerationService;
import com.ram.trading.signal.engine.service.TradingSignalService;
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

    @Override
    public Mono<TradingSignal> generateSignal(String symbol) {

        log.info("========== SIGNAL GENERATION STARTED ==========");
        log.info("Symbol : {}", symbol);

        return marketDataProvider
                .getStockPrice(symbol)

                .doOnNext(stock ->
                        log.info(
                                "Live Price : {} -> {}",
                                stock.getSymbol(),
                                stock.getPrice()))

                .zipWhen(stock ->
                        technicalIndicatorService
                                .calculate(symbol)
                                .switchIfEmpty(Mono.defer(() -> {

                                    log.warn(
                                            "Skipping {} because technical indicators are unavailable.",
                                            symbol);

                                    return Mono.empty();
                                }))
                )

                .flatMap(tuple -> {

                    StockResponse stock = tuple.getT1();
                    TechnicalIndicatorResponse indicator = tuple.getT2();

                    SignalGenerationRequest request =
                            buildSignalRequest(
                                    stock,
                                    indicator);

                    log.debug("Technical Indicators Loaded");
                    log.debug("RSI      : {}", indicator.getRsi14());
                    log.debug("EMA20    : {}", indicator.getEma20());
                    log.debug("EMA50    : {}", indicator.getEma50());
                    log.debug("SMA20    : {}", indicator.getSma20());
                    log.debug("SMA50    : {}", indicator.getSma50());
                    log.debug("MACD     : {}", indicator.getMacd());

                    /*
                     * IMPORTANT:
                     *
                     * TradingContext is NOT created here.
                     *
                     * Technical Decision
                     *        ↓
                     * Engineering Filter
                     *        ↓
                     * Trading Context
                     *        ↓
                     * AI
                     *
                     * TradingOrchestratorService owns this flow.
                     */

                    return generateTradingSignal(
                            request,
                            indicator);
                })

                .doOnSuccess(signal -> {

                    if (signal != null) {

                        log.info(
                                "Signal Generated Successfully : {} -> {}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.info(
                            "========== SIGNAL GENERATION COMPLETED ==========");
                })

                .doOnError(error ->
                        log.error(
                                "Signal generation failed for {}",
                                symbol,
                                error));
    }

    @Override
    public Mono<TradingSignal> generateSignal(Tick tick) {

        log.info(
                "========== LIVE SIGNAL GENERATION STARTED ==========");

        log.info(
                "Symbol : {}",
                tick.getSymbol());

        log.info(
                "LTP    : {}",
                tick.getLastTradedPrice());

        final long start =
                System.currentTimeMillis();

        return tradeExitService
                .evaluateExit(tick)

                .then(
                        technicalIndicatorService
                                .calculate(tick.getSymbol())

                                .switchIfEmpty(Mono.defer(() -> {

                                    log.warn(
                                            "Skipping {} because technical indicators are unavailable.",
                                            tick.getSymbol());

                                    return Mono.empty();
                                }))

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
                                                    indicator);

                                    /*
                                     * IMPORTANT:
                                     *
                                     * Do NOT build TradingContext here.
                                     *
                                     * Engineering filtering happens
                                     * inside TradingOrchestratorService
                                     * before expensive context calls.
                                     */

                                    return generateTradingSignal(
                                            request,
                                            indicator);
                                })
                )

                .doOnSuccess(signal -> {

                    if (signal != null) {

                        log.info(
                                "Live Signal Generated : {} -> {}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.info(
                            "========== LIVE SIGNAL GENERATION COMPLETED ==========");
                })

                .doOnError(error ->
                        log.error(
                                "Live Signal Generation Failed for {}",
                                tick.getSymbol(),
                                error))

                .doFinally(signalType ->
                        log.info(
                                "TOTAL Live Signal Processing Time for {} : {} ms",
                                tick.getSymbol(),
                                System.currentTimeMillis() - start));
    }

    private Mono<TradingSignal> generateTradingSignal(
            SignalGenerationRequest request,
            TechnicalIndicatorResponse indicator) {

        log.debug(
                "======================================================");

        log.debug(
                "Trading Decision Pipeline Started");

        log.info(
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
                     * ENGINEERING FILTER REJECTION
                     * =====================================================
                     *
                     * Orchestrator returns Mono.empty() for rejected
                     * symbols, so normally this block won't be reached
                     * for rejected symbols.
                     *
                     * Still keeping this validation makes the service
                     * defensive.
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

                    log.debug(
                            "Trade Allowed : {}",
                            aiResponse
                                    .getDecision()
                                    .getTradeAllowed());

                    log.debug(
                            "Recommendation : {}",
                            aiResponse
                                    .getDecision()
                                    .getRecommendation());

                    logAiDecision(aiResponse);

                    /*
                     * =====================================================
                     * GET EXISTING TRADING CONTEXT
                     * =====================================================
                     *
                     * IMPORTANT:
                     *
                     * DO NOT call:
                     *
                     * tradingContextService.buildTradingContext(...)
                     *
                     * here.
                     *
                     * The context has already been created by
                     * TradingOrchestratorService and is carried inside
                     * TradingPipelineResult.
                     *
                     * This prevents duplicate:
                     *
                     * News
                     * Portfolio
                     * Open Position
                     *
                     * calls.
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
                                    .build();

                    try {

                        log.debug(
                                "STEP-1 Before Risk Guard");

                        RiskGuardResult result =
                                riskGuardService.evaluate(
                                        evaluation);

                        log.debug(
                                "STEP-2 Risk Guard Completed");

                        logRiskEvaluation(result);

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

                        log.debug(
                                "STEP-5 Before Post Process");

                        return postProcessSignal(
                                signal,
                                indicator);

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
            TechnicalIndicatorResponse indicator) {

        log.info("========== POST PROCESSING ==========");

        if (SignalType.HOLD.name().equals(signal.getSignal())) {
            log.info("Signal is HOLD. Nothing to persist.");
            return Mono.just(signal);
        }

        log.info("Saving Trading Signal...");

        return Mono.fromCallable(() -> tradingSignalService.save(signal))
                .subscribeOn(Schedulers.boundedElastic())

                .flatMap(entity -> {

                    log.info("Trading Signal Saved : {}", entity.getId());

                    return Mono.fromRunnable(() -> {

                                log.info("Saving Opportunity...");

                                opportunityService.save(
                                        signal,
                                        entity.getId());

                                log.info("Opportunity Saved Successfully.");

                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .thenReturn(entity);

                })

                .flatMap(entity ->

                        Mono.fromRunnable(() -> {

                                    log.info("Creating Paper Trade...");

                                    paperTradingService.createTrade(
                                            entity,
                                            indicator);

                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .thenReturn(signal)
                )

                .doOnSuccess(s ->
                        log.info("Post Processing Completed Successfully."))

                .doOnError(ex ->
                        log.error("Post Processing Failed", ex));
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
                .build();
    }

    private SignalGenerationRequest buildSignalRequest(
            Tick tick,
            TechnicalIndicatorResponse indicator) {

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
                .build();
    }

}