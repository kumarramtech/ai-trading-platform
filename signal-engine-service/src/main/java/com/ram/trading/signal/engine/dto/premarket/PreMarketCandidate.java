package com.ram.trading.signal.engine.dto.premarket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreMarketCandidate {

    private String tradingSymbol;

    private Double previousClose;

    private Double preMarketPrice;

    private Double gapPercentage;

    private String direction;

    private int score;

    private String status;

    private LocalDateTime discoveredAt;
}