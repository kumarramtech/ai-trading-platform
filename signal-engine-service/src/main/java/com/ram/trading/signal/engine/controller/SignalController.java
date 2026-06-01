package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.service.StockServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/signals")
@RequiredArgsConstructor
public class SignalController {

    private final StockServiceClient stockServiceClient;

    @GetMapping("/{symbol}")
    public Mono<TradingSignal> generateSignal(
            @PathVariable String symbol) {


        return stockServiceClient.getStockPrice().
                map(stock-> {
                        Double price = stock.getPrice();
                        String signal;
                    if (price < 1000) {
                        signal = "BUY";
                    } else if (price > 3000) {
                        signal = "SELL";
                    } else {
                        signal = "HOLD";
                    }
                    return new TradingSignal(
                            stock.getSymbol(),
                            signal,
                            price,
                            price * 1.02,
                            price * 0.99
                    );
                }          );
    }
}