package com.ram.trading.signal.engine.exit;


import com.ram.trading.signal.engine.dto.market.MarketTick;
import com.ram.trading.signal.engine.dto.market.OpenPosition;

public interface ExitStrategy {

    ExitDecision evaluate(OpenPosition position,
                          MarketTick marketTick);

}