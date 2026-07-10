package com.ram.trading.stock.bootstrap.history;

import com.ram.trading.stock.dto.history.HistoricalCandleResponse;

import java.time.LocalDate;

public interface HistoricalPricePersistenceService {

    void save(HistoricalCandleResponse response);

    LocalDate getLastDownloadedDate(String symbol);

}