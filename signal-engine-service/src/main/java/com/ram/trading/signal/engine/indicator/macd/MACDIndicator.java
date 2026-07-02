package com.ram.trading.signal.engine.indicator.macd;

import com.ram.trading.signal.engine.dto.indicator.Candle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MACDIndicator {

    private final MACDCalculator calculator;

    public MACDResult calculate(List<Candle> candles) {

        return calculator.calculate(candles);

    }
}