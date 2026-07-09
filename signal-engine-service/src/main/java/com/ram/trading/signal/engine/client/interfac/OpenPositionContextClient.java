package com.ram.trading.signal.engine.client.interfac;

import com.ram.trading.signal.engine.dto.ai.portfolio.OpenPositionContextResponse;
import reactor.core.publisher.Mono;

public interface OpenPositionContextClient {

    Mono<OpenPositionContextResponse> getOpenPositionContext(String symbol);

}