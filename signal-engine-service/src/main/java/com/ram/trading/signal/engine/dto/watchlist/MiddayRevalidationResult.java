package com.ram.trading.signal.engine.dto.watchlist;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MiddayRevalidationResult {

    private String tradingSymbol;

    private String direction;

    private Double discoveryPrice;

    private Double currentPrice;

    private Double directionalMovementPct;

    private Double rsi;

    private Double ema20;

    private Double ema50;

    private Double macd;

    private Double signalLine;

    private int score;

    private String status;

    private String reason;
}