package com.ram.trading.backtesting.repo;

import com.ram.trading.backtesting.entity.TechnicalIndicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TechnicalIndicatorRepository
        extends JpaRepository<TechnicalIndicator, Long> {

    List<TechnicalIndicator>
    findBySymbolOrderByTradeDateAsc(String symbol);
}