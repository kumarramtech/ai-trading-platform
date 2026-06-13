package com.ram.trading.signal.engine.dto;

import lombok.*;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OpportunityResponse {

    private String symbol;

    private String signal;

    private Integer confidence;

    private Double targetPrice;

    private Double stopLoss;
}