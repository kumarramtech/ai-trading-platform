package com.ram.trading.market.data.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MarketRegimeSnapshot {
    private Double niftyChange;
    private Double bankNiftyChange;
    private String regime;
    private LocalDateTime updatedAt;
}
