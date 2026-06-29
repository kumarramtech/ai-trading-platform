package com.ram.trading.stock.service;

import com.ram.trading.stock.dto.StockResponse;
import reactor.core.publisher.Mono;

import java.util.List;

public interface MarketDataProvider {

    Mono<StockResponse> getPrice(String symbol);

    List<StockResponse> getAllStocks();
}