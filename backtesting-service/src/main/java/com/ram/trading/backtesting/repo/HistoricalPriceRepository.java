package com.ram.trading.backtesting.repo;

import com.ram.trading.backtesting.entity.HistoricalPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistoricalPriceRepository
        extends JpaRepository<HistoricalPrice, Long> {

    List<HistoricalPrice>
    findBySymbolOrderByTradeDateAsc(String symbol);
}