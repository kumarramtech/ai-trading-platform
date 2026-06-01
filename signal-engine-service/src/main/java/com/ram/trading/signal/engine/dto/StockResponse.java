package com.ram.trading.signal.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockResponse {

    private String symbol;
    private Double price;
    public Double getPrice(String symbol) {

        return switch (symbol.toUpperCase()) {
            case "TCS" -> 4120.50;
            case "INFY" -> 1580.75;
            case "HDFC" -> 1890.25;
            default -> 1000.00;
        };
    }
}