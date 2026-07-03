package com.ram.trading.signal.engine.dto.ai.execution;

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