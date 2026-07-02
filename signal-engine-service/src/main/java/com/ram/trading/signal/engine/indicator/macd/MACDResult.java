package com.ram.trading.signal.engine.indicator.macd;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MACDResult {

    private BigDecimal macd;

    private BigDecimal signal;

    private BigDecimal histogram;

    private IndicatorSignal trend;
}