package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.history.HistoricalPriceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/market-data")
public class MarketDataController {

    private final StockServiceClient stockServiceClient;

    @GetMapping("/{symbol}/history")
    public Flux<HistoricalPriceResponse> getHistory(
            @PathVariable String symbol) {

        return stockServiceClient.getHistoricalPrices(symbol);

    }

}