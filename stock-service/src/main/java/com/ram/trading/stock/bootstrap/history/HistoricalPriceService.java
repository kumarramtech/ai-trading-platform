package com.ram.trading.stock.bootstrap.history;

import com.ram.trading.stock.bootstrap.entity.HistoricalPrice;

import java.time.LocalDate;
import java.util.List;

public interface HistoricalPriceService {

    void saveAll(List<HistoricalPrice> prices);

    List<HistoricalPrice> findBySymbol(String symbol);

    void deleteBySymbol(String symbol);

    List<HistoricalPrice> findBySymbolAndDateRange(
            String symbol,
            LocalDate from,
            LocalDate to);

    List<HistoricalPrice> findLatest(
            String symbol,
            Integer limit);

    void flush();
}