package com.ram.trading.signal.engine.dto.ai.technical;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MacdAnalysis {

    private Double value;

    private Double signalLine;

    private String interpretation;

}