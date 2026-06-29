package com.ram.trading.market.data.service;

import com.ram.trading.market.data.dto.PriceResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class MarketDataService {

    public PriceResponse getPrice(String symbol) {

        return PriceResponse.builder()
                .symbol(symbol)
                .price(1600.0)
                .source("MOCK")
                .timestamp(LocalDateTime.now())
                .build();
    }
}