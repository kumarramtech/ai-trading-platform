package com.ram.trading.market.data.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class Tick {

    private String exchange;

    private String symbol;

    private String instrumentKey;

    private Double lastTradedPrice;

    private Double openPrice;

    private Double highPrice;

    private Double lowPrice;

    private Double closePrice;

    private Long volume;

    private Long timestamp;

}