package com.ram.trading.signal.engine.strategy;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;

public class BasicTradingStrategy implements TradingStrategy {
    @Override
    public TradingSignal generateSignal(StockResponse stock) {
        return null;
    }
}