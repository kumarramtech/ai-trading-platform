package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.WatchlistRequest;
import com.ram.trading.signal.engine.entity.WatchlistStock;
import com.ram.trading.signal.engine.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService service;

    @PostMapping
    public WatchlistStock addStock(
            @RequestBody WatchlistRequest request) {

        return service.addStock(
                request.getSymbol());
    }

    @GetMapping
    public List<WatchlistStock> getAll() {

        return service.getAllStocks();
    }

    @DeleteMapping("/{symbol}")
    public String delete(
            @PathVariable String symbol) {

        service.removeStock(symbol);

        return "Deleted : " + symbol;
    }
}