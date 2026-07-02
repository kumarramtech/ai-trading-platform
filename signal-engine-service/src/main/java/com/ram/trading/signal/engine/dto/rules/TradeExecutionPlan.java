package com.ram.trading.signal.engine.dto.rules;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeExecutionPlan {

    private Double entryPrice;

    private Double stopLoss;

    private Double targetPrice;

    private Double quantity;

    private Double riskRewardRatio;

}