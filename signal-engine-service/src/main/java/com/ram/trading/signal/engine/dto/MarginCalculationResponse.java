package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MarginCalculationResponse {

    private String instrumentKey;

    private Integer quantity;

    private String transactionType;

    private String product;

    private Double price;

    private Double tradeValue;

    private Double requiredMargin;

    private Double finalMargin;

    private Double availableBalance;

    private Double leverage;

    private Boolean sufficientBalance;

    private Integer maximumQuantity;
}