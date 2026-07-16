package com.ram.trading.market.data.dto;

import lombok.Data;

@Data
public class InstrumentLookupResponse {

    private String instrumentKey;

    private String tradingSymbol;

}