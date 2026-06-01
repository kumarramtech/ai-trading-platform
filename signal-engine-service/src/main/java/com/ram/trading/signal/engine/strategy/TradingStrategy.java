package com.ram.trading.signal.engine.strategy;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;

public interface TradingStrategy {
    TradingSignal generateSignal(StockResponse stock);
}