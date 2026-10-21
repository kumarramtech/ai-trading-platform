package com.ram.trading.trade.execution.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("quantity")
    private Integer quantity;

    @JsonProperty("product")
    private String product;

    @JsonProperty("validity")
    private String validity;

    @JsonProperty("price")
    private BigDecimal price;

    @JsonProperty("tag")
    private String tag;

    @JsonProperty("instrument_token")
    private String instrumentToken;

    @JsonProperty("order_type")
    private String orderType;

    @JsonProperty("transaction_type")
    private String transactionType;

    @JsonProperty("disclosed_quantity")
    private Integer disclosedQuantity;

    @JsonProperty("trigger_price")
    private BigDecimal triggerPrice;

    @JsonProperty("is_amo")
    private Boolean isAmo;

    @JsonProperty("slice")
    private Boolean slice;
}
