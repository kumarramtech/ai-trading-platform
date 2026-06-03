package com.ram.trading.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StockResponse {

    private String symbol;
    private Double price;
}