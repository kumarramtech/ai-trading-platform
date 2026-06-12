package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.client.AIServiceClient;
import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.dto.*;
import com.ram.trading.signal.engine.service.interfac.AIAnalysisService;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.service.PaperTradingService;
import com.ram.trading.signal.engine.service.SignalStatService;
import com.ram.trading.signal.engine.service.TradingSignalService;
import com.ram.trading.signal.engine.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/signals")
@RequiredArgsConstructor
public class SignalController {

    private final MarketDataProvider marketDataProvider;

    private final TradingSignalService tradingSignalService;

    private final TradingStrategy tradingStrategy;

    private final SignalStatService signalStatService;

    private final PaperTradingService paperTradingService;

    private final IndicatorClient indicatorClient;

    private final AIAnalysisService aiAnalysisService;

    private final AIServiceClient aiServiceClient;

    @GetMapping("/{symbol}")
    public Mono<TradingSignal> generateSignal(
            @PathVariable String symbol) {

        return marketDataProvider.getStockPrice(symbol).
                flatMap(stock ->
                        tradingStrategy.generateSignal(stock)
                                .flatMap(signal -> {
                                    if (SignalType.HOLD.name()
                                            .equals(signal.getSignal())) {
                                        return Mono.just(signal);
                                    }
                                    TradingSignalEntity savedEntity = tradingSignalService.save(signal);
                                    return indicatorClient.getLatest(signal.getSymbol())
                                            .map(indicator -> {
                                                paperTradingService.createTrade(savedEntity, indicator);
                                                return signal;
                                            });
                                }));
    }

    @GetMapping("/history/{symbol}")
    public List<TradingSignalEntity> getHistory(
            @PathVariable String symbol) {
        return tradingSignalService.getHistory(symbol);
    }

    @GetMapping("/history/open")
    public List<TradingSignalEntity> getOpenSignals() {

        return tradingSignalService.findByStatus("OPEN");
    }

    @GetMapping("/stats")
    public SignalStats getSignalStat() {

        return signalStatService.getStats();
    }

    @GetMapping("/{symbol}/explanation")
    public Mono<SignalExplanation> explainSignal(
            @PathVariable String symbol) {

        return marketDataProvider
                .getStockPrice(symbol)
                .flatMap(stock -> tradingStrategy
                        .generateSignal(stock)
                        .flatMap(signal -> indicatorClient.getLatest(symbol)
                                .map(indicator -> aiAnalysisService
                                        .explainSignal(signal, indicator))));
    }

    @GetMapping("/{symbol}/ai-explanation")
    public Mono<SignalExplanationResponse> getAIExplanation(
            @PathVariable String symbol) {

        return marketDataProvider
                .getStockPrice(symbol)
                .flatMap(stock ->
                        tradingStrategy.generateSignal(stock)
                                .flatMap(signal -> indicatorClient
                                                .getLatest(symbol)
                                                .flatMap(indicator -> {
                                                    SignalExplanationRequest request =
                                                            SignalExplanationRequest
                                                                    .builder()
                                                                    .symbol(signal.getSymbol())
                                                                    .signal(signal.getSignal())
                                                                    .confidence(signal.getConfidence())
                                                                    .rsi(indicator.getRsi14())
                                                                    .ema20(indicator.getEma20())
                                                                    .ema50(indicator.getEma50())
                                                                    .macd(indicator.getMacd())
                                                                    .build();

                                                    return aiServiceClient
                                                            .explainSignal(request);
                                                })));
    }

}