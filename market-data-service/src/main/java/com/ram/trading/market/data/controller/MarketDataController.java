package com.ram.trading.market.data.controller;

import com.ram.trading.market.data.dto.PriceResponse;
import com.ram.trading.market.data.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/market-data")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService service;

    @GetMapping("/price/{symbol}")
    public PriceResponse getPrice(
            @PathVariable String symbol) {

        return service.getPrice(symbol);
    }
}