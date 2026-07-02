package com.ram.trading.signal.engine.indicator.service;

import com.ram.trading.signal.engine.contant.IndicatorType;
import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.dto.indicator.IndicatorResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndicatorService {

    private final IndicatorFactory factory;

    public IndicatorResult calculate(
            IndicatorType type,
            List<Candle> candles,
            int period) {
       return factory.getIndicator(type).calculate(candles, period);

    }

}