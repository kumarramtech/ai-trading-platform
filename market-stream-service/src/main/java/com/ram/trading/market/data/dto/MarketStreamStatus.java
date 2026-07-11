package com.ram.trading.market.data.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class MarketStreamStatus {

    private String provider;

    private String connectionStatus;

    private String marketStatus;

    private Integer cacheSize;

    private Long ticksProcessed;

    private Long uptime;
}