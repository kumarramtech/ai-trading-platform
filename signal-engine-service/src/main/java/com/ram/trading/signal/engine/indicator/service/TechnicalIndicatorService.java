package com.ram.trading.signal.engine.indicator.service;

import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import reactor.core.publisher.Mono;

public interface TechnicalIndicatorService {
    Mono<TechnicalIndicatorResponse> calculate(String symbol);

}