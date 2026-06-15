package com.ram.trading.signal.engine.dto;

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
}