package com.ram.trading.ai.engine.dto.portfolio;

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