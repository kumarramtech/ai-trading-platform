package com.ram.trading.ai.engine.dto.technical;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmaAnalysis {

    private Double ema20;

    private Double ema50;

    private String trend;

}