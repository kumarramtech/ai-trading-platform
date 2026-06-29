package com.ram.trading.stock.controller;

import com.ram.trading.stock.dto.StockResponse;
import com.ram.trading.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @GetMapping("/stocks/{symbol}")
    public Mono<StockResponse> getStock(
            @PathVariable String symbol) {

        return stockService.getPrice(symbol);
    }

    @GetMapping("/stocks/allstocks")
    public Flux<StockResponse> getAllStocks() {

        return Flux.fromIterable(
                stockService.getAllStocks());
    }
}
