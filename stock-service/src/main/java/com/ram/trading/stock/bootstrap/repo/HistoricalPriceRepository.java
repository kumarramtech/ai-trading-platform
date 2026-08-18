package com.ram.trading.stock.bootstrap.repo;

import com.ram.trading.stock.bootstrap.entity.HistoricalPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HistoricalPriceRepository
        extends JpaRepository<HistoricalPrice, Long> {

    List<HistoricalPrice> findBySymbolOrderByTradeDateAsc(
            String symbol);

    List<HistoricalPrice> findBySymbolAndIntervalTypeOrderByTradeDateAsc(
            String symbol,
            String intervalType);

    List<HistoricalPrice> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
            String symbol,
            LocalDate from,
            LocalDate to);

    List<HistoricalPrice> findBySymbolAndIntervalTypeAndTradeDateBetweenOrderByTradeDateAsc(
            String symbol,
            String intervalType,
            LocalDate from,
            LocalDate to);

    long countBySymbol(String symbol);

    boolean existsBySymbolAndTradeDateAndIntervalType(
            String symbol,
            LocalDate tradeDate,
            String intervalType);

    void deleteBySymbol(String symbol);

    List<HistoricalPrice>
    findTop100BySymbolOrderByTradeDateDesc(
            String symbol);

    Optional<HistoricalPrice>
    findTopBySymbolOrderByTradeDateDesc(
            String symbol);
}