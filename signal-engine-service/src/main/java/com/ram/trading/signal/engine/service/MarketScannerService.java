package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.dto.RiskCheckResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.strategy.TradingStrategy;
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
    private final TradingStrategy tradingStrategy;
    private final TradingSignalService tradingSignalService;
    private final PaperTradingService paperTradingService;
    private final IndicatorClient indicatorClient;
    private final RiskManagementService riskManagementService;

    private static final List<String> WATCHLIST =
            List.of(
                    "TCS",
                    "INFY",
                    "RELIANCE",
                    "HDFC",
                    "ICICIBANK"
            );

    public void scanMarket() {
        log.info("Scanning {} symbols", WATCHLIST.size());
        WATCHLIST.forEach(this::scanSymbol);
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
                .flatMap(stock ->
                        tradingStrategy.generateSignal(stock)
                                .flatMap(this::processSignal))
                .subscribe(
                        result ->
                                log.info(
                                        "Scan Completed {}",
                                        symbol),
                        error ->
                                log.error(
                                        "Scanner failed for {}",
                                        symbol,
                                        error));
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

        if (!riskCheck.isAllowed()) {

            log.warn(
                    "Trade blocked : {}",
                    riskCheck.getViolations());

            return Mono.just(signal);
        }

        TradingSignalEntity savedSignal =
                tradingSignalService.save(signal);

        return indicatorClient
                .getLatest(signal.getSymbol())
                .onErrorResume(error -> {
                    log.warn(
                            "Indicator not available for {}",
                            signal.getSymbol());
                    return Mono.empty();
                })
                .map(indicator -> {
                    paperTradingService
                            .createTrade(
                                    savedSignal,
                                    indicator);
                    log.info(
                            "Trade Created : {} {}",
                            signal.getSymbol(),
                            signal.getSignal());

                    return signal;
                });
    }
}