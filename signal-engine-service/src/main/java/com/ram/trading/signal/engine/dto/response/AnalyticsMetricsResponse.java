package com.ram.trading.signal.engine.dto.response;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsMetricsResponse {

    private double profitFactor;

    private double expectancy;

    private double maxDrawdown;

    private long consecutiveWins;

    private long consecutiveLosses;
}