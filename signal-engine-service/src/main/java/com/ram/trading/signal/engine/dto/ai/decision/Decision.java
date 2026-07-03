package com.ram.trading.signal.engine.dto.ai.decision;

import com.ram.trading.signal.engine.contant.AiRecommendation;
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