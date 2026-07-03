package com.ram.trading.ai.engine.dto.technical;

import com.ram.trading.ai.engine.constant.enums.SignalType;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalAnalysis {

    private String summary;

    private SignalType signal;

    private RsiAnalysis rsi;

    private EmaAnalysis ema;

    private MacdAnalysis macd;

    private VolumeAnalysis volume;

}