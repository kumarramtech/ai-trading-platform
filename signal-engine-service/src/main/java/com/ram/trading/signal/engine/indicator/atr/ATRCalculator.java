package com.ram.trading.signal.engine.indicator.atr;

import com.ram.trading.signal.engine.dto.indicator.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class ATRCalculator {

    public BigDecimal calculate(List<Candle> candles, int period) {

        BigDecimal totalTR = BigDecimal.ZERO;

        for (int i = candles.size() - period; i < candles.size(); i++) {

            Candle current = candles.get(i);
            Candle previous = candles.get(i - 1);

            BigDecimal tr1 =
                    current.getHigh().subtract(current.getLow()).abs();

            BigDecimal tr2 =
                    current.getHigh().subtract(previous.getClose()).abs();

            BigDecimal tr3 =
                    current.getLow().subtract(previous.getClose()).abs();

            BigDecimal trueRange =
                    tr1.max(tr2).max(tr3);

            totalTR = totalTR.add(trueRange);
        }

        return totalTR.divide(
                BigDecimal.valueOf(period),
                2,RoundingMode.HALF_UP);
    }
}