package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TradeInsights {

    private Double averageWinningRsi;

    private Double averageLosingRsi;

    private Double averageWinningMacd;

    private Double averageLosingMacd;

    private Integer bestConfidence;

    private Integer worstConfidence;

}