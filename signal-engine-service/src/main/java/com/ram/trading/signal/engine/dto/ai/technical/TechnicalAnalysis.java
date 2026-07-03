package com.ram.trading.signal.engine.dto.ai.technical;

import com.ram.trading.signal.engine.contant.SignalType;
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