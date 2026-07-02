package com.ram.trading.signal.engine.indicator.rsi;

import com.ram.trading.signal.engine.contant.IndicatorSignal;
import com.ram.trading.signal.engine.contant.IndicatorType;
import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.dto.indicator.IndicatorResult;
import com.ram.trading.signal.engine.indicator.service.Indicator;
import com.ram.trading.signal.engine.indicator.util.IndicatorValidationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;


@RequiredArgsConstructor
@Slf4j
@Component
public class RSIIndicator implements Indicator {

    private final RSICalculator calculator;
    @Override
    public IndicatorType getType() {
        return IndicatorType.RSI;
    }

    @Override
    public IndicatorResult calculate(List<Candle> candles,int period) {

        log.info("========== RSI DEBUG ==========");
        log.info("Total Candles : {}", candles.size());

        IndicatorValidationUtil.validate(candles, period);

        BigDecimal value =
                calculator.calculate(
                        candles,
                        period);

        IndicatorSignal signal;

        if (value.compareTo(BigDecimal.valueOf(70)) > 0) {

            signal = IndicatorSignal.OVERBOUGHT;

        } else if (value.compareTo(BigDecimal.valueOf(30)) < 0) {

            signal = IndicatorSignal.OVERSOLD;

        } else {

            signal = IndicatorSignal.NEUTRAL;

        }

        return IndicatorResult.builder()
                .type(IndicatorType.RSI)
                .period(period)
                .value(value)
                .signal(signal)
                .build();

    }

}
