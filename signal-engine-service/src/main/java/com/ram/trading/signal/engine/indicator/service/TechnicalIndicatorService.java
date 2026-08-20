package com.ram.trading.signal.engine.indicator.service;

import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface TechnicalIndicatorService {

    Mono<TechnicalIndicatorResponse> calculate(String symbol);

    Flux<TechnicalIndicatorResponse> calculateBulk(
            List<String> symbols);
}