package com.ram.trading.portfolio.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPositionRequest {

    private String symbol;

    private Integer quantity;

    private Double entryPrice;
}