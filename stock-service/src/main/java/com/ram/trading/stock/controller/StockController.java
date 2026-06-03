package com.ram.trading.stock.controller;

import com.ram.trading.stock.dto.StockResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StockController {

    private static final Map<String, Double> STOCK_PRICES =
            Map.of(
                    "TCS", 4120.5,
                    "HDFC", 1750.0,
                    "INFY", 1600.0
            );

    @GetMapping("/stocks/{symbol}")
    public StockResponse getStock(
            @PathVariable String symbol) {
        double price =
                STOCK_PRICES.getOrDefault(
                        symbol.toUpperCase(),
                        1000.0);

        return new StockResponse(symbol, price);
    }
}
