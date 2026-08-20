package com.ram.trading.signal.engine.dto.watchlist;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WatchlistStockResponse {

    private Long id;
    private String symbol;
    private boolean active;
    private LocalDateTime createdAt;
}