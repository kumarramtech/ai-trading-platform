package com.ram.trading.signal.engine.service.context;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class TradingContext {

    private String newsSummary;

    private String sectorSummary;

    private String portfolioSummary;

    private String riskSummary;

    private String openPositionsSummary;

}