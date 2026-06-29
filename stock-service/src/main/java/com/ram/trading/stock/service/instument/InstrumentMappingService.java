package com.ram.trading.stock.service.instument;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class InstrumentMappingService {

    private static final Map<String, String> INSTRUMENTS = Map.of(
            "INFY", "NSE_EQ|INE009A01021",
            "TCS", "NSE_EQ|INE467B01029",
            "RELIANCE", "NSE_EQ|INE002A01018",
            "HDFCBANK", "NSE_EQ|INE040A01034",
            "ICICIBANK", "NSE_EQ|INE090A01021",
            "SBIN", "NSE_EQ|INE062A01020"
    );

    public String getInstrumentKey(String symbol) {

        String instrumentKey = INSTRUMENTS.get(symbol.toUpperCase());

        if (instrumentKey == null) {
            throw new IllegalArgumentException(
                    "Instrument not found for symbol : " + symbol);
        }

        return instrumentKey;
    }
}