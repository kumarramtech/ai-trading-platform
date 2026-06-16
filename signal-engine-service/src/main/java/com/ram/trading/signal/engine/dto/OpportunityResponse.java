package com.ram.trading.signal.engine.dto;

import com.ram.trading.signal.engine.contant.Recommendation;
import lombok.*;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OpportunityResponse {
    private Integer rank;
    private String symbol;
    private String signal;
    private Integer confidence;
    private Double targetPrice;
    private Double stopLoss;
    private Integer score;
    private Double entryPrice;
    private Integer sentimentScore;
    private String sentiment;
    private String technicalReason;
    private String sentimentReason;
    private Recommendation recommendation;
}