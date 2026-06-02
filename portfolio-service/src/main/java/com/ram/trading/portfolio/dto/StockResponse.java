package com.ram.trading.portfolio.dto;

import lombok.Data;

@Data
public class StockResponse {

    private String symbol;
    private Double price;
}