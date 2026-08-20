package com.ram.trading.watchlist.controller;

import com.ram.trading.watchlist.dto.WatchlistResponse;
import com.ram.trading.watchlist.entity.WatchlistStock;
import com.ram.trading.watchlist.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @PostMapping("/generate")
    public Mono<WatchlistResponse> generateWatchlist() {

        return watchlistService.generateWatchlist();
    }

    @GetMapping("/active")
    public List<WatchlistStock> getActiveWatchlist() {

        return watchlistService.getAllStocks();
    }
}