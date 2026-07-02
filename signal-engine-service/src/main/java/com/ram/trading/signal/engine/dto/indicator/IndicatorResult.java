package com.ram.trading.signal.engine.dto.indicator;

import com.ram.trading.signal.engine.contant.IndicatorSignal;
import com.ram.trading.signal.engine.contant.IndicatorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorResult {

    private IndicatorType type;

    private BigDecimal value;

    private IndicatorSignal signal;

    private Integer period;

}