package com.ram.trading.market.data.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class HealthResponse {

    private String status;

    private String stream;

    private String provider;

    private String connection;

    private Integer cacheSize;

    private Long ticksProcessed;

    private Long uptime;

}