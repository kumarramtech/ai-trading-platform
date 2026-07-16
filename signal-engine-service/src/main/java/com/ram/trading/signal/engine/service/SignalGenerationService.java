package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.market.Tick;
import reactor.core.publisher.Mono;

public interface SignalGenerationService {

    Mono<TradingSignal> generateSignal(String symbol);

    Mono<TradingSignal> generateSignal(Tick event);

}