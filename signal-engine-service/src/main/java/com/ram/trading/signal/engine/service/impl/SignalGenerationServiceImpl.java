package com.ram.trading.signal.engine.service.impl;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.RiskCheckResponse;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;

import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import com.ram.trading.signal.engine.service.PaperTradingService;
import com.ram.trading.signal.engine.service.RiskManagementService;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalGenerationServiceImpl
        implements SignalGenerationService {

    private final MarketDataProvider marketDataProvider;

    private final TradingOrchestratorService tradingOrchestratorService;

    private final TradingSignalMapper tradingSignalMapper;

    private final TradingSignalService tradingSignalService;

    private final TechnicalIndicatorService technicalIndicatorService;

    private final PaperTradingService paperTradingService;

    private final RiskManagementService riskManagementService;

    private final TradingContextService tradingContextService;

    @Override
    public Mono<TradingSignal> generateSignal(String symbol) {

        log.info("Generating Trading Signal for {}", symbol);

        return marketDataProvider
                .getStockPrice(symbol)
                .zipWhen(stock -> technicalIndicatorService.calculate(symbol))
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

    private Mono<TradingSignal> generateTradingSignal(
            SignalGenerationRequest request,
            TradingContext context) {
        log.info("========== Trading Context ==========");
        log.info("News      : {}", context.getNewsSummary());
        log.info("Sector    : {}", context.getSectorSummary());
        log.info("Portfolio Risk : {}", context.getPortfolioContext().getRisk().getRiskLevel());
        log.info("Portfolio Health : {}",context.getPortfolioContext().getHealth().getStatus());
        log.info("Portfolio Summary : {}", context.getPortfolioContext().getSummary());
        log.info("Portfolio Recommendations : {}",context.getPortfolioContext().getRecommendations());
        log.info("====================================");
        return tradingOrchestratorService
                .executeTrade(
                        request,
                        context)
                .map(aiResponse ->
                        tradingSignalMapper.map(
                                aiResponse,
                                request))
                .flatMap(this::postProcessSignal);
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

                .map(indicator -> {

                    RiskCheckResponse riskCheck =
                            riskManagementService.validateTrade();

                    if (!riskCheck.isAllowed()) {

                        log.warn(
                                "Trade blocked due to {}",
                                riskCheck.getViolations());

                        return signal;
                    }

                    paperTradingService.createTrade(
                            entity,
                            indicator);

                    return signal;

                });

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

}