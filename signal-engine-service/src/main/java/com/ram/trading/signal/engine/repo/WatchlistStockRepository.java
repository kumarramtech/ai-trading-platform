package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.entity.WatchlistStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WatchlistStockRepository
        extends JpaRepository<WatchlistStock, Long> {

    List<WatchlistStock> findByActiveTrue();

    boolean existsBySymbol(String symbol);

    void deleteBySymbol(String symbol);
}