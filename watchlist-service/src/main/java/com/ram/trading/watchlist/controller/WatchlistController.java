package com.ram.trading.watchlist.controller;

import com.ram.trading.watchlist.dto.TrendingStock;
import com.ram.trading.watchlist.dto.WatchlistResponse;
import com.ram.trading.watchlist.entity.WatchlistStock;
import com.ram.trading.watchlist.service.TrendingStockService;
import com.ram.trading.watchlist.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    private final TrendingStockService trendingStockService;

    @PostMapping("/generate")
    public Mono<WatchlistResponse> generateWatchlist() {

        return watchlistService.generateWatchlist();
    }

    @GetMapping("/active")
    public List<WatchlistStock> getActiveWatchlist() {

        return watchlistService.getAllStocks();
    }


    @GetMapping("/trending")
    public Mono<List<TrendingStock>> getTrendingStocks(
            @RequestParam(
                    name = "limit",
                    defaultValue = "20")
            int limit) {

        return trendingStockService.getTrendingStocks(limit);
    }
}