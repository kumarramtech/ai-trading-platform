package com.ram.trading.ai.engine.dto;

import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AiDecisionContext {

    private TradingDecisionRequest request;

    private AiDecisionResponse response;

    private String prompt;

    private String rawResponse;

    private Long executionTime;

}