package com.ram.trading.signal.engine.dto.ai.risk;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAnalysis {

    private String riskLevel;

    private String riskRewardRatio;

    private Boolean stopLossRequired;

    private List<String> risks;

}