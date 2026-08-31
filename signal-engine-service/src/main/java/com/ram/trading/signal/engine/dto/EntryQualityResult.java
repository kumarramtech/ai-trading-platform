package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EntryQualityResult {

    String symbol;

    String direction;

    Double signalPrice;

    Double freshMarketPrice;

    Double directionalMovementPct;

    Double priceVsEma20Pct;

    Double priceVsEma50Pct;

    Double priceVsHistoricalClosePct;

    String status;

    String reason;
}