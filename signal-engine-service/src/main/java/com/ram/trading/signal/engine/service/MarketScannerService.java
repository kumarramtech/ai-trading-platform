package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.NewsAnalysisClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.request.NewsAnalysisRequest;
import com.ram.trading.signal.engine.risk.RiskCheckResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.entity.WatchlistStock;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.repo.WatchlistStockRepository;
import com.ram.trading.signal.engine.service.ai.TradingOrchestratorService;
import com.ram.trading.signal.engine.service.ai.mapper.TradingSignalMapper;
import com.ram.trading.signal.engine.service.context.TradingContextService;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.util.SignalConfidenceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketScannerService {

    private final MarketDataProvider marketDataProvider;
    private final SignalPreparationService signalPreparationService;
    private final TradingSignalService tradingSignalService;
    private final TradingOrchestratorService tradingOrchestratorService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final RiskManagementService riskManagementService;
    private final NewsAnalysisClient newsAnalysisClient;
    private final SignalConfidenceCalculator confidenceCalculator;
    private final PaperTradeRepository paperTradeRepository;
    private final WatchlistStockRepository watchlistRepository;
    private final OpportunityService opportunityService;
    private final TradingSignalMapper tradingSignalMapper;
    private final TradingContextService tradingContextService;

    public void scanMarket() {
        List<WatchlistStock> stocks =
                watchlistRepository.findByActiveTrue();
        log.info("Scanning {} symbols",stocks.size());
        stocks.forEach(stock -> scanSymbol(stock.getSymbol()));
    }

    private void scanSymbol(String symbol) {
        log.info("Scanning Symbol = {}", symbol);
        marketDataProvider
                .getStockPrice(symbol)
                .doOnNext(stock ->
                        log.info(
                                "Price Received {} = {}",
                                stock.getSymbol(),
                                stock.getPrice()))
                .flatMap(stock ->signalPreparationService.prepare(stock)
                        .flatMap(signalRequest ->
                                createTradingSignal(signalRequest)
                                        .flatMap(this::processSignal))
                )
                .subscribe(result -> log.info("Scan Completed {}",symbol),
                        error -> log.error("Scanner failed for {}",symbol,error));
    }

    private Mono<TradingSignal> processSignal(
            TradingSignal signal) {
        log.info(
                "Signal Generated => {} {} Confidence={}",
                signal.getSymbol(),
                signal.getSignal(),
                signal.getConfidence());
        if ("HOLD".equals(signal.getSignal())) {

            log.info(
                    "{} -> HOLD",
                    signal.getSymbol());

            return Mono.just(signal);
        }

        RiskCheckResponse riskCheck =
                riskManagementService.validateTrade();
        log.info(
                "Risk Allowed={} Violations={}",
                riskCheck.isAllowed(),
                riskCheck.getViolations());

        if (!riskCheck.isAllowed()) {

            log.warn(
                    "Trade blocked : {}",
                    riskCheck.getViolations());

            return Mono.just(signal);
        }

        NewsAnalysisRequest request =
                NewsAnalysisRequest.builder()
                        .symbol(signal.getSymbol())
                        .headline(
                                signal.getReason())
                        .build();

        return newsAnalysisClient
                .analyze(request)
                .flatMap(news -> {

                    Integer technicalScore =
                            signal.getConfidence();

                    Integer finalConfidence =
                            confidenceCalculator.calculate(
                                    technicalScore,
                                    news.getScore());

                    log.info(
                            "Technical Score={} News Score={} Final={}",
                            technicalScore,
                            news.getScore(),
                            finalConfidence);

                    signal.setConfidence(finalConfidence);
                    signal.setNewsScore(news.getScore());
                    signal.setNewsSentiment(news.getSentiment());
                    signal.setNewsSummary(news.getSummary());
                    //opportunityService.save(signal);
                    return technicalIndicatorService
                            .calculate(signal.getSymbol())
                            .onErrorResume(error -> {
                                log.warn("Indicator not available for {}",signal.getSymbol());
                                return Mono.empty();
                            })
                            .map(indicator -> {
                                TradingSignalEntity savedSignal =tradingSignalService.save(signal);
                                log.info("Signal Saved ID={}",savedSignal.getId());
                                opportunityService.save(signal,savedSignal.getId());
                                boolean tradeExists =
                                        paperTradeRepository.existsBySymbolAndStatus(signal.getSymbol(),
                                                        SignalStatus.OPEN);
                                if (tradeExists) {
                                    log.info("Skipping {} because open trade already exists",signal.getSymbol());
                                    return signal;
                                }
                                //TradingSignalEntity savedSignal =tradingSignalService.save(signal);
                                //log.info("Signal Saved ID={}", savedSignal.getId());
                                //opportunityService.save(signal);
                                log.info("Trade Created : {} {}",signal.getSymbol(),signal.getSignal());
                                return signal;
                            });
                });
    }

    private Mono<TradingSignal> createTradingSignal(
            SignalGenerationRequest request) {

        return tradingContextService
                .buildTradingContext(request.getSymbol())
                .flatMap(context ->
                        tradingOrchestratorService
                                .executeTrade(
                                        request,
                                        context)
                                .map(aiDecisionResponse ->
                                        tradingSignalMapper.map(
                                                aiDecisionResponse,
                                                request)));

    }
}