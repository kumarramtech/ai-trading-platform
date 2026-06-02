package com.ram.trading.signal.engine.strategy;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import org.springframework.stereotype.Service;

@Service
public class BasicTradingStrategy implements TradingStrategy {
    @Override
    public TradingSignal generateSignal(StockResponse stock) {
        Double price = stock.getPrice();

        String signal;

        if (price < 1000) {
            signal = "BUY";
        } else if (price > 3000) {
            signal = "SELL";
        } else {
            signal = "HOLD";
        }

        return new TradingSignal(
                stock.getSymbol(),
                signal,
                price,
                price * 1.02,
                price * 0.99,
                "Price above threshold",
                80
        );
    }
}
