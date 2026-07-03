package com.ram.trading.stock.bootstrap.repo;



import com.ram.trading.stock.bootstrap.entity.HistoricalPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface HistoricalPriceRepository extends JpaRepository<HistoricalPrice,Long> {

    List<HistoricalPrice> findBySymbolOrderByTradeDateAsc(
            String symbol);

    List<HistoricalPrice> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
            String symbol,
            LocalDate from,
            LocalDate to);

    boolean existsBySymbolAndTradeDateAndIntervalType(
            String symbol,
            LocalDate tradeDate,
            String intervalType);

    void deleteBySymbol(String symbol);

    List<HistoricalPrice> findTop100BySymbolOrderByTradeDateDesc(
            String symbol);

}