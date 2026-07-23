package com.ram.trading.signal.engine.service.impl;

import com.ram.trading.signal.engine.contant.SignalType;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
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

    @Override
    public Mono<TradingSignal> generateSignal(String symbol) {

        log.info("Generating Trading Signal for {}", symbol);

        return marketDataProvider
                .getStockPrice(symbol)
                .zipWhen(stock ->
                        technicalIndicatorService
                                .calculate(symbol)
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.warn("Skipping {} due to insufficient historical data.", symbol);
                                    return Mono.empty();
                                }))
                )
                .flatMap(tuple -> {

                    StockResponse stock = tuple.getT1();
                    TechnicalIndicatorResponse indicator = tuple.getT2();

                    SignalGenerationRequest request =
                            buildSignalRequest(stock, indicator);

                    return tradingContextService
                            .buildTradingContext(symbol)
                            .flatMap(context ->
                                    generateTradingSignal(request, context));
                });
    }

    @Override
    public Mono<TradingSignal> generateSignal(Tick tick) {

        log.info("Generating Trading Signal from Live Tick : {}", tick.getSymbol());

        return tradeExitService
                .evaluateExit(tick)
                .then(
                        technicalIndicatorService
                                .calculate(tick.getSymbol())
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.warn("Skipping {} due to insufficient historical data.",
                                            tick.getSymbol());
                                    return Mono.empty();
                                }))
                                .flatMap(indicator -> {

                                    SignalGenerationRequest request =
                                            buildSignalRequest(tick, indicator);

                                    return tradingContextService
                                            .buildTradingContext(tick.getSymbol())
                                            .flatMap(context ->
                                                    generateTradingSignal(
                                                            request,
                                                            context));
                                })
                );
    }

    private Mono<TradingSignal> generateTradingSignal(
            SignalGenerationRequest request,
            TradingContext context) {

        log.info("========== Trading Context ==========");
        log.info("News      : {}", context.getNewsSummary());
        log.info("Sector    : {}", context.getSectorSummary());

        PortfolioContextResponse portfolio = context.getPortfolioContext();

        if (portfolio != null) {

            if (portfolio.getSummary() != null) {
                log.info("Portfolio Summary : {}", portfolio.getSummary());
            }

            if (portfolio.getRecommendations() != null) {
                log.info("Portfolio Recommendations : {}", portfolio.getRecommendations());
            }

            if (portfolio.getRisk() != null) {
                log.info("Portfolio Risk : {}", portfolio.getRisk().getRiskLevel());
            }

            if (portfolio.getHealth() != null) {
                log.info("Portfolio Health : {}", portfolio.getHealth().getStatus());
            }

        } else {
            log.info("Portfolio Context : Not Available");
        }

        log.info("News Score : {}", context.getNewsScore());
        log.info("News Sentiment : {}", context.getNewsSentiment());

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

        return tradingOrchestratorService
                .executeTrade(request)
                .flatMap(aiResponse -> {

                    // ===== AI RESPONSE LOGS =====
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

                    log.info("AI Reasoning        : {}", aiResponse.getAiReasoning());

                    log.info("Technical Analysis  : {}", aiResponse.getTechnicalAnalysis());

                    log.info("Risk Analysis       : {}", aiResponse.getRiskAnalysis());

                    log.info("News Analysis       : {}", aiResponse.getNewsAnalysis());

                    log.info("Portfolio Analysis  : {}", aiResponse.getPortfolioAnalysis());

                    log.info("Execution Plan      : {}", aiResponse.getExecutionPlan());

                    log.info("=================================");

                    RiskEvaluation evaluation =
                            RiskEvaluation.builder()
                                    .context(context)
                                    .decision(aiResponse.getDecision())
                                    .aiResponse(aiResponse)
                                    .build();

                    RiskGuardResult result =
                            riskGuardService.evaluate(evaluation);

                    // ===== RISK GUARD LOGS =====
                    log.info("========== RISK GUARD ==========");
                    log.info("Approved : {}", result.isApproved());

                    if (!result.isApproved()) {
                        result.getViolations().forEach(v ->
                                log.warn("{} -> {}",
                                        v.getRule(),
                                        v.getReason()));
                    }

                    log.info("================================");

                    TradingSignal signal =
                            tradingSignalMapper.map(aiResponse, request);

                    if (!result.isApproved()) {

                        log.warn("Trade rejected by Risk Guard");

                        signal.setSignal(SignalType.HOLD.name());

                        signal.setReason(
                                result.getViolations()
                                        .stream()
                                        .map(RiskViolation::getReason)
                                        .collect(Collectors.joining(", "))
                        );

                        return Mono.just(signal);
                    }

                    return postProcessSignal(signal);
                });
    }


    private Mono<TradingSignal> postProcessSignal(
            TradingSignal signal) {

        if (SignalType.HOLD.name().equals(signal.getSignal())) {
            log.info("Signal is HOLD. Skipping save.");
            return Mono.just(signal);
        }

        TradingSignalEntity entity =
                tradingSignalService.save(signal);

        return technicalIndicatorService
                .calculate(signal.getSymbol())
                .flatMap(indicator -> {

                    paperTradingService.createTrade(
                            entity,
                            indicator);

                    log.info("Paper Trade created successfully.");

                    return Mono.just(signal);
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {

                    log.warn("Skipping Paper Trade for {} due to insufficient indicator data.",
                            signal.getSymbol());

                    return signal;
                }));
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
                .build();
    }

}