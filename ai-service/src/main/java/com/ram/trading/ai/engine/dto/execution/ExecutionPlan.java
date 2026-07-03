package com.ram.trading.ai.engine.dto.execution;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {

    private Double entry;

    private Double stopLoss;

    private Double target;

    private Integer positionSize;

    private String holdingPeriod;

    private String exitStrategy;

}