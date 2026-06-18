package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.entity.WatchlistStock;
import com.ram.trading.signal.engine.repo.WatchlistStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistStockRepository repository;

    public WatchlistStock addStock(
            String symbol) {

        if (repository.existsBySymbol(symbol)) {
            throw new RuntimeException(
                    "Stock already exists");
        }

        WatchlistStock stock =
                WatchlistStock.builder()
                        .symbol(symbol.toUpperCase())
                        .active(true)
                        .createdAt(LocalDateTime.now())
                        .build();

        return repository.save(stock);
    }

    public List<WatchlistStock> getAllStocks() {

        return repository.findByActiveTrue();
    }

    public void removeStock(
            String symbol) {

        repository.deleteBySymbol(
                symbol.toUpperCase());
    }
}