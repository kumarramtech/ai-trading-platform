package com.ram.trading.watchlist.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WatchlistMarketQuoteResponse {

    private String status;

    private Map<String, WatchlistQuoteData> data;
}