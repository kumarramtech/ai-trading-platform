package com.ram.trading.stock.service.history;

import com.ram.trading.stock.dto.history.HistoricalCandleResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

public interface HistoricalCandleService {

    Mono<HistoricalCandleResponse> getHistoricalCandles(
            String symbol,
            String interval,
            LocalDate from,
            LocalDate to);

}