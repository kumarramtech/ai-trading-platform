package com.ram.trading.stock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarketQuoteResponse {

    private String status;

    private Map<String, QuoteData> data;
}