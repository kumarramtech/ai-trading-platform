package com.ram.trading.signal.engine.indicator.service;

import com.ram.trading.signal.engine.contant.IndicatorType;
import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.dto.indicator.IndicatorResult;

import java.util.List;

public interface Indicator {

    IndicatorType getType();

    IndicatorResult calculate(List<Candle> candles,int period);

}