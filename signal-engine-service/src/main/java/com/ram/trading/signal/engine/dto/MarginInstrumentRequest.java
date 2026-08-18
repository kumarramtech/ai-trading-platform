package com.ram.trading.signal.engine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MarginInstrumentRequest {

    @JsonProperty("instrument_key")
    private String instrumentKey;

    private Integer quantity;

    @JsonProperty("transaction_type")
    private String transactionType;

    private String product;

    private BigDecimal price;
}