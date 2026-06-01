package com.ram.trading.stock.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StockController {

    @GetMapping("/stocks/{symbol}")
    public Map<String, Object> getStock(@PathVariable String symbol) {
        return Map.of(
                "symbol", symbol,
                "price", 4120.50
        );
    }
}
