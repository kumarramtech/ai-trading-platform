package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankedOpportunity {

    private Integer rank;

    private String symbol;

    private String signal;

    private Integer confidence;

    private Integer newsScore;

    private String sentiment;
}