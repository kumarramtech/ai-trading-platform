package com.ram.trading.signal.engine.indicator.ema;

import com.ram.trading.signal.engine.contant.IndicatorSignal;
import com.ram.trading.signal.engine.contant.IndicatorType;
import com.ram.trading.signal.engine.dto.indicator.Candle;

import com.ram.trading.signal.engine.dto.indicator.IndicatorResult;
import com.ram.trading.signal.engine.indicator.service.Indicator;
import com.ram.trading.signal.engine.indicator.util.IndicatorValidationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public class EMAIndicator implements Indicator {

    private final EMACalculator calculator;

    @Override
    public IndicatorResult calculate(List<Candle> candles,
                                     int period) {

        IndicatorValidationUtil.validate(candles, period);
        BigDecimal value = calculator.calculate(candles, period);

        return IndicatorResult.builder()
                .type(IndicatorType.EMA)
                .period(period)
                .value(value)
                .signal(IndicatorSignal.NEUTRAL)
                .build();
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.EMA;
    }
}