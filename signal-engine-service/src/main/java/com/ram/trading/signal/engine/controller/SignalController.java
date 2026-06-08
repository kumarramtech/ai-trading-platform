package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.SignalStats;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.service.SignalStatService;
import com.ram.trading.signal.engine.client.StockServiceClient;
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

    private final StockServiceClient stockServiceClient;

    private final TradingSignalService tradingSignalHistoryService;

    private final TradingStrategy tradingStrategy;

    private final SignalStatService signalStatService;

    @GetMapping("/{symbol}")
    public Mono<TradingSignal> generateSignal(
            @PathVariable String symbol) {

        return stockServiceClient
                .getStockPrice(symbol)
                .flatMap(stock ->

                        tradingStrategy
                                .generateSignal(stock)
                                .map(signal -> {

                                    tradingSignalHistoryService
                                            .save(signal);

                                    return signal;
                                })
                );
    }

    @GetMapping("/history/{symbol}")
    public List<TradingSignalEntity> getHistory(
            @PathVariable String symbol) {
        return tradingSignalHistoryService.getHistory(symbol);
    }

    @GetMapping("/history/open")
    public List<TradingSignalEntity> getOpenSignals() {

        return tradingSignalHistoryService.findByStatus("OPEN");
    }

    @GetMapping("/stats")
    public SignalStats getSignalStat() {

        return signalStatService.getStats();
    }

}