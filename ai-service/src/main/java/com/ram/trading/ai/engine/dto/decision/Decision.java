package com.ram.trading.ai.engine.dto.decision;

import com.ram.trading.ai.engine.constant.AiRecommendation;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Decision {

    private Boolean tradeAllowed;

    private AiRecommendation recommendation;

    private Integer confidence;

    private String decisionStrength;

}