package com.ram.trading.signal.engine.dto;

import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DailyPnLResponse {

    private Double todayProfit;

    private Long totalTrades;

    private Long winningTrades;

    private Long losingTrades;

    private Long breakevenTrades;
}