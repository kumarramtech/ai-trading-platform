package com.ram.trading.stock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuoteData {

    @JsonProperty("last_price")
    private Double lastPrice;

    @JsonProperty("instrument_token")
    private String instrumentToken;
}