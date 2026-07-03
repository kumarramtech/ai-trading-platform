package com.ram.trading.signal.engine.dto.ai.portfolio;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioAnalysis {

    private String currentExposure;

    private String availableCapital;

    private String recommendation;

}