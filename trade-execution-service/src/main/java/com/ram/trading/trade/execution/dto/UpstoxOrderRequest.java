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
public class UpstoxOrderRequest {

    private Integer quantity;

    private String product;

    private String validity;

    private BigDecimal price;

    private String instrumentToken;

    private String orderType;

    private String transactionType;
}