package com.ram.trading.signal.engine.dto;

import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PositionSizingResponse {

    private String symbol;

    private Double capital;

    private Integer confidence;

    private Double recommendedInvestment;

    private Integer recommendedQuantity;

    private Double riskPerShare;

    private Double totalRisk;
}