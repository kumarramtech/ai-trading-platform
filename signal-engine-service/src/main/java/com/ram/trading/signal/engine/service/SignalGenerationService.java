package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.watchlist.MiddayRevalidationResult;
import com.ram.trading.signal.engine.dto.market.Tick;
import reactor.core.publisher.Mono;

public interface SignalGenerationService {

    Mono<TradingSignal> generateSignal(String symbol);

    Mono<TradingSignal> generateSignal(Tick event);

    /**
     * Generates a trading decision for a QUALIFIED midday candidate.
     * This uses the same Engineering -> AI -> Risk -> Position Sizing
     * pipeline as the live tick-based entry flow.
     */
    Mono<TradingSignal> generateMiddaySignal(MiddayRevalidationResult result);

}