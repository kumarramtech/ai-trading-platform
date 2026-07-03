package com.ram.trading.signal.engine.dto.ai.technical;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RsiAnalysis {

    private Double value;

    private String interpretation;

}