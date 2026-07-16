package com.ram.trading.signal.engine.exit;

import com.ram.trading.signal.engine.dto.market.Tick;
import reactor.core.publisher.Mono;

public interface TradeExitService {

    Mono<Void> evaluateExit(Tick tick);

}