package com.ram.trading.signal.engine.strategy;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;


public interface TradingStrategy {
    Mono<TradingSignal> generateSignal(
            StockResponse stock);
}