package com.ram.trading.signal.engine.dto;

import com.ram.trading.signal.engine.contant.Recommendation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpportunityDashboard {

    private Integer rank;
    private String symbol;
    private String signal;
    private Integer confidence;
    private Integer tradeScore;
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
    private Recommendation recommendation;
    private Double riskRewardRatio;
}