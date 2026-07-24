package com.ram.trading.signal.engine.service.impl;

import com.ram.trading.signal.engine.contant.SignalType;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.portfolio.PortfolioContextResponse;
import com.ram.trading.signal.engine.exit.TradeExitService;
import com.ram.trading.signal.engine.risk.RiskViolation;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
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
import com.ram.trading.signal.engine.service.context.TradingContextService;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

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

    private final TradingContextService tradingContextService;

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
                        log.info("Live Price : {} -> {}",
                                stock.getSymbol(),
                                stock.getPrice()))

                .zipWhen(stock ->
                        technicalIndicatorService
                                .calculate(symbol)
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.warn("Skipping {} because technical indicators are unavailable.",
                                            symbol);
                                    return Mono.empty();
                                }))
                )

                .flatMap(tuple -> {

                    StockResponse stock = tuple.getT1();
                    TechnicalIndicatorResponse indicator = tuple.getT2();

                    SignalGenerationRequest request =
                            buildSignalRequest(stock, indicator);

                    log.info("Technical Indicators Loaded");
                    log.info("RSI      : {}", indicator.getRsi14());
                    log.info("EMA20    : {}", indicator.getEma20());
                    log.info("EMA50    : {}", indicator.getEma50());
                    log.info("SMA20    : {}", indicator.getSma20());
                    log.info("SMA50    : {}", indicator.getSma50());
                    log.info("MACD     : {}", indicator.getMacd());

                    return tradingContextService
                            .buildTradingContext(symbol)
                            .flatMap(context ->
                                    generateTradingSignal(
                                            request,
                                            context));
                })

                .doOnSuccess(signal -> {

                    if (signal != null) {
                        log.info("Signal Generated Successfully : {} -> {}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.info("========== SIGNAL GENERATION COMPLETED ==========");
                })

                .doOnError(error ->
                        log.error("Signal generation failed for {}",
                                symbol,
                                error));
    }

    @Override
    public Mono<TradingSignal> generateSignal(Tick tick) {

        log.info("========== LIVE SIGNAL GENERATION STARTED ==========");
        log.info("Symbol : {}", tick.getSymbol());
        log.info("LTP    : {}", tick.getLastTradedPrice());

        return tradeExitService
                .evaluateExit(tick)

                .then(
                        technicalIndicatorService
                                .calculate(tick.getSymbol())
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.warn("Skipping {} because technical indicators are unavailable.",
                                            tick.getSymbol());
                                    return Mono.empty();
                                }))

                                .flatMap(indicator -> {

                                    log.info("Technical Indicators Loaded");
                                    log.info("RSI      : {}", indicator.getRsi14());
                                    log.info("EMA20    : {}", indicator.getEma20());
                                    log.info("EMA50    : {}", indicator.getEma50());
                                    log.info("SMA20    : {}", indicator.getSma20());
                                    log.info("SMA50    : {}", indicator.getSma50());
                                    log.info("MACD     : {}", indicator.getMacd());

                                    SignalGenerationRequest request =
                                            buildSignalRequest(
                                                    tick,
                                                    indicator);

                                    return tradingContextService
                                            .buildTradingContext(
                                                    tick.getSymbol())
                                            .flatMap(context ->
                                                    generateTradingSignal(
                                                            request,
                                                            context));
                                })
                )

                .doOnSuccess(signal -> {

                    if (signal != null) {
                        log.info("Live Signal Generated : {} -> {}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.info("========== LIVE SIGNAL GENERATION COMPLETED ==========");
                })

                .doOnError(error ->
                        log.error("Live Signal Generation Failed for {}",
                                tick.getSymbol(),
                                error));
    }

    private Mono<TradingSignal> generateTradingSignal(
            SignalGenerationRequest request,
            TradingContext context) {

        log.info("======================================================");
        log.info("Trading Decision Pipeline Started");
        log.info("Symbol : {}", request.getSymbol());
        log.info("======================================================");

        logTradingContext(context);

        return tradingOrchestratorService
                .executeTrade(request)

                .flatMap(aiResponse -> {

                    log.info("******** AI RESPONSE RECEIVED ********");
                    log.info("Trade Allowed : {}", aiResponse.getDecision().getTradeAllowed());
                    log.info("Recommendation : {}", aiResponse.getDecision().getRecommendation());
                    logAiDecision(aiResponse);

                    RiskEvaluation evaluation =
                            RiskEvaluation.builder()
                                    .context(context)
                                    .decision(aiResponse.getDecision())
                                    .aiResponse(aiResponse)
                                    .build();

                    try {

                        log.info("STEP-1 Before Risk Guard");

                        RiskGuardResult result =
                                riskGuardService.evaluate(evaluation);

                        log.info("STEP-2 Risk Guard Completed");

                        logRiskEvaluation(result);

                        log.info("STEP-3 Before Mapper");

                        TradingSignal signal =
                                tradingSignalMapper.map(aiResponse, request);

                        log.info("STEP-4 Mapper Completed");

                        if (!result.isApproved()) {
                            signal.setSignal(SignalType.HOLD.name());
                            log.warn("Trade rejected by Risk Guard. Returning HOLD.");
                            return Mono.just(signal);
                        }

                        log.info("STEP-5 Before Post Process");

                        return postProcessSignal(signal);

                    } catch (Exception ex) {

                        log.error("FAILED INSIDE generateTradingSignal()", ex);

                        return Mono.error(ex);
                    }

                })

                .doOnSuccess(signal -> {

                    if (signal != null) {
                        log.info("Final Decision : {} {}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.info("Trading Decision Pipeline Completed");
                })

                .doOnError(error ->
                        log.error("Trading Decision Pipeline Failed",
                                error));
    }

    private void logTradingContext(TradingContext context) {

        log.info("========== Trading Context ==========");

        log.info("News      : {}", context.getNewsSummary());
        log.info("Sector    : {}", context.getSectorSummary());

        PortfolioContextResponse portfolio =
                context.getPortfolioContext();

        if (portfolio != null) {

            if (portfolio.getSummary() != null) {
                log.info("Portfolio Summary : {}",
                        portfolio.getSummary());
            }

            if (portfolio.getRecommendations() != null) {
                log.info("Portfolio Recommendations : {}",
                        portfolio.getRecommendations());
            }

            if (portfolio.getRisk() != null) {
                log.info("Portfolio Risk : {}",
                        portfolio.getRisk().getRiskLevel());
            }

            if (portfolio.getHealth() != null) {
                log.info("Portfolio Health : {}",
                        portfolio.getHealth().getStatus());
            }

        } else {

            log.info("Portfolio Context : Not Available");
        }

        log.info("News Score : {}",
                context.getNewsScore());

        log.info("News Sentiment : {}",
                context.getNewsSentiment());

        if (context.getOpenPositionContext() != null) {

            log.info("Open Position Exists : {}",
                    context.getOpenPositionContext().isPositionExists());

            if (context.getOpenPositionContext().isPositionExists()) {

                log.info("Open Position Signal : {}",
                        context.getOpenPositionContext().getSignal());

                log.info("Current PnL : {}",
                        context.getOpenPositionContext().getCurrentPnL());
            }

        } else {

            log.info("Open Position Context : Not Available");
        }

        log.info("====================================");
    }

    private void logAiDecision(AiDecisionResponse aiResponse) {

        log.info("========== AI RESPONSE ==========");

        if (aiResponse.getDecision() != null) {

            log.info("Trade Allowed      : {}",
                    aiResponse.getDecision().getTradeAllowed());

            log.info("Recommendation     : {}",
                    aiResponse.getDecision().getRecommendation());

            log.info("Confidence         : {}",
                    aiResponse.getDecision().getConfidence());

            log.info("Decision Strength  : {}",
                    aiResponse.getDecision().getDecisionStrength());

        } else {

            log.warn("Decision : NULL");
        }

        log.info("AI Reasoning       : {}",
                aiResponse.getAiReasoning());

        log.info("Technical Analysis : {}",
                aiResponse.getTechnicalAnalysis());

        log.info("Risk Analysis      : {}",
                aiResponse.getRiskAnalysis());

        log.info("News Analysis      : {}",
                aiResponse.getNewsAnalysis());

        log.info("Portfolio Analysis : {}",
                aiResponse.getPortfolioAnalysis());

        log.info("Execution Plan     : {}",
                aiResponse.getExecutionPlan());

        log.info("=================================");
    }

    private void logRiskEvaluation(
            RiskGuardResult result) {

        log.info("========== RISK GUARD ==========");

        log.info("Approved : {}",
                result.isApproved());

        if (!result.isApproved()) {

            result.getViolations()
                    .forEach(v ->
                            log.warn("{} -> {}",
                                    v.getRule(),
                                    v.getReason()));
        }

        log.info("================================");
    }

    private Mono<TradingSignal> postProcessSignal(TradingSignal signal) {

        log.info("========== POST PROCESSING ==========");

        if (SignalType.HOLD.name().equals(signal.getSignal())) {

            log.info("Signal is HOLD. Nothing to persist.");

            return Mono.just(signal);
        }

        log.info("Saving Trading Signal...");

        TradingSignalEntity entity = tradingSignalService.save(signal);

        log.info("Trading Signal Saved : {}", entity.getId());

        try {

            log.info("Saving Opportunity...");

            opportunityService.save(signal, entity.getId());

            log.info("Opportunity Saved Successfully.");

        } catch (Exception ex) {

            log.error("Unable to save Opportunity", ex);
        }

        log.info("Calling TechnicalIndicatorService for Paper Trade...");

        return technicalIndicatorService

                .calculate(signal.getSymbol())

                .doOnSubscribe(subscription ->
                        log.info("Subscribed to TechnicalIndicatorService"))

                .doOnSuccess(indicator -> {

                    if (indicator == null) {
                        log.warn("TechnicalIndicatorService returned NULL");
                    } else {
                        log.info("TechnicalIndicatorService returned indicator successfully.");
                    }

                })

                .doOnError(ex ->
                        log.error("TechnicalIndicatorService Error", ex))

                .flatMap(indicator -> {

                    log.info("Inside flatMap()");
                    log.info("About to call PaperTradingService.createTrade()");

                    try {

                        paperTradingService.createTrade(entity, indicator);

                        log.info("Returned Successfully from createTrade()");

                    } catch (Exception ex) {

                        log.error("Paper Trade Creation Failed", ex);
                    }

                    return Mono.just(signal);

                })

                .switchIfEmpty(

                        Mono.fromSupplier(() -> {

                            log.warn("TechnicalIndicatorService returned EMPTY.");
                            log.warn("createTrade() was NOT called.");

                            return signal;

                        })

                )

                .doOnSuccess(s ->
                        log.info("POST PROCESSING COMPLETED"))

                .doOnTerminate(() ->
                        log.info("========== POST PROCESSING FINISHED =========="));
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