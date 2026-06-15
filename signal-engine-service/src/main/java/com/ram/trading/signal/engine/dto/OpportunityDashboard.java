package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpportunityDashboard {

    private String symbol;
    private String signal;
    private Integer confidence;
    private Integer score;
    private Double entryPrice;
    private Double targetPrice;
    private Double stopLoss;
    private Double recommendedInvestment;
    private Integer recommendedQuantity;
    private Double riskPerShare;
    private Double totalRisk;
    private String sentiment;
    private Integer sentimentScore;
    private String technicalReason;
    private String sentimentReason;
}