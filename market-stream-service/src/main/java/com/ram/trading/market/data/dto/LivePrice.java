package com.ram.trading.market.data.dto;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class LivePrice {

    private String symbol;

    private Double price;

    private Double change;

    private Double changePercentage;

    private Long volume;

    private Long timestamp;

}