package com.ram.trading.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class StockResponse {

    private String symbol;
    private Double price;
}