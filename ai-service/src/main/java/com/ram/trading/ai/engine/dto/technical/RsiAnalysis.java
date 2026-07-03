package com.ram.trading.ai.engine.dto.technical;

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