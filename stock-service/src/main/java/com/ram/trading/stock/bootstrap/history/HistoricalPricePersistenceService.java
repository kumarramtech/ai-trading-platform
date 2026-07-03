package com.ram.trading.stock.bootstrap.history;

import com.ram.trading.stock.dto.history.HistoricalCandleResponse;

public interface HistoricalPricePersistenceService {

    void save(HistoricalCandleResponse response);

}