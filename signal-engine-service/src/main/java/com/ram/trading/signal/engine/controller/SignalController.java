package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.service.StockServiceClient;
import com.ram.trading.signal.engine.service.TradingSignalService;
import com.ram.trading.signal.engine.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

    @GetMapping("/{symbol}")
    public Mono<TradingSignal> generateSignal(
            @PathVariable String symbol) {

        return stockServiceClient.getStockPrice(symbol).
                map(stock-> {
                    TradingSignal tradeSignal = tradingStrategy.generateSignal(stock);
                    tradingSignalHistoryService.save(tradeSignal);
                    return tradeSignal;
                }          );
    }

    @GetMapping("/history/{symbol}")
    public List<TradingSignalEntity> getHistory(
            @PathVariable String symbol) {
        return tradingSignalHistoryService.getHistory(symbol);
    }

}