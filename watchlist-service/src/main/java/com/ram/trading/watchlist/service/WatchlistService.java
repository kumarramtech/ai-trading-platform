package com.ram.trading.watchlist.service;

import com.ram.trading.watchlist.dto.WatchlistResponse;
import com.ram.trading.watchlist.entity.WatchlistStock;
import reactor.core.publisher.Mono;

import java.util.List;

public interface WatchlistService {

    Mono<WatchlistResponse> generateWatchlist();
    public void removeStock(String symbol);
    public List<WatchlistStock> getAllStocks();
    public WatchlistStock addStock(String symbol);
    void replaceWatchlist(List<String> symbols);
}