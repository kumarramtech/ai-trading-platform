package com.ram.trading.watchlist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradableInstrumentResponse {

    private String tradingSymbol;

    private String companyName;

    private String instrumentKey;

    private String exchange;

    private String segment;

    private String instrumentType;
}