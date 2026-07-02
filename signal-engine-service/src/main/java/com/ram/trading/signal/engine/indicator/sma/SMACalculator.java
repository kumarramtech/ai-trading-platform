package com.ram.trading.signal.engine.indicator.sma;


import com.ram.trading.signal.engine.dto.indicator.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class SMACalculator {

    public BigDecimal calculate(List<Candle> candles, int period) {

        BigDecimal sum = BigDecimal.ZERO;

        for (int i = candles.size() - period; i < candles.size(); i++) {

            sum = sum.add(candles.get(i).getClose());

        }

        return sum.divide(
                BigDecimal.valueOf(period),
                2,
                RoundingMode.HALF_UP);

    }

}