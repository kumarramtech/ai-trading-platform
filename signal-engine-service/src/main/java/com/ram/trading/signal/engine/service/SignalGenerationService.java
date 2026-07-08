package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.TradingSignal;
import reactor.core.publisher.Mono;

public interface SignalGenerationService {

    Mono<TradingSignal> generateSignal(String symbol);

}