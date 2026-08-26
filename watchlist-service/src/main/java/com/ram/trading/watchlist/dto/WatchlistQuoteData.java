package com.ram.trading.watchlist.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WatchlistQuoteData {

    @JsonProperty("ohlc")
    private WatchlistOhlc ohlc;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("instrument_token")
    private String instrumentToken;

    @JsonProperty("symbol")
    private String symbol;

    @JsonProperty("last_price")
    private Double lastPrice;

    @JsonProperty("volume")
    private Long volume;

    @JsonProperty("average_price")
    private Double averagePrice;

    @JsonProperty("net_change")
    private Double netChange;

    @JsonProperty("total_buy_quantity")
    private Long totalBuyQuantity;

    @JsonProperty("total_sell_quantity")
    private Long totalSellQuantity;

    @JsonProperty("last_trade_time")
    private String lastTradeTime;
}