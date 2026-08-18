package com.ram.trading.trade.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerOrderRequest {

    private String symbol;

    private String instrumentKey;

    private String signal;

    private Integer quantity;

    private BigDecimal price;

    private String orderType;
}