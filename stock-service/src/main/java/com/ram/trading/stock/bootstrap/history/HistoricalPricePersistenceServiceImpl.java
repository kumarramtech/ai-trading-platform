package com.ram.trading.stock.bootstrap.history;

import com.ram.trading.stock.bootstrap.CandleInterval;
import com.ram.trading.stock.bootstrap.entity.HistoricalPrice;
import com.ram.trading.stock.dto.history.Candle;
import com.ram.trading.stock.dto.history.HistoricalCandleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalPricePersistenceServiceImpl
        implements HistoricalPricePersistenceService {

    private final HistoricalPriceService historicalPriceService;

    @Override
    @Transactional
    public void save(HistoricalCandleResponse response) {

        if (response == null
                || response.getCandles() == null
                || response.getCandles().isEmpty()) {

            log.warn(
                    "No historical candles received for {}",
                    response != null
                            ? response.getSymbol()
                            : "UNKNOWN");

            return;
        }

        String symbol = response.getSymbol();

        log.info("Historical candles received for persistence | " + "Symbol={} | Received={}",
                symbol,response.getCandles().size());

        /*
         * Get all existing candles for this symbol.
         */
        Set<LocalDate> existingDates =
                historicalPriceService
                        .findBySymbol(symbol)
                        .stream()
                        .filter(price ->
                                CandleInterval.DAY.equals(
                                        price.getIntervalType()))
                        .map(HistoricalPrice::getTradeDate)
                        .collect(Collectors.toSet());

        /*
         * Convert only candles that do not already exist
         * in the database.
         */
        List<HistoricalPrice> pricesToSave =
                response.getCandles()
                        .stream()
                        .filter(candle ->
                                !existingDates.contains(
                                        candle.getDateTime()
                                                .toLocalDate()))
                        .map(candle ->
                                mapToEntity(
                                        candle,
                                        symbol))
                        .toList();

        if (pricesToSave.isEmpty()) {

            log.info(
                    "No new historical candles to persist | " +
                            "Symbol={} | Received={} | Existing={}",
                    symbol,
                    response.getCandles().size(),
                    existingDates.size());

            return;
        }

        log.info(
                "Persisting new historical candles | " +
                        "Symbol={} | Received={} | New={} | Skipped={}",
                symbol,
                response.getCandles().size(),
                pricesToSave.size(),
                response.getCandles().size()
                        - pricesToSave.size());

        historicalPriceService.saveAll(pricesToSave);

        log.info(
                "Successfully persisted {} new historical candles for {}",
                pricesToSave.size(),
                symbol);
    }

    @Override
    public LocalDate getLastDownloadedDate(String symbol) {

        return historicalPriceService.getLastDownloadedDate(symbol);
    }

    @Override
    public long countBySymbol(String symbol) {
        return historicalPriceService.countBySymbol(symbol);
    }

    private HistoricalPrice mapToEntity(Candle candle,String symbol) {

        return HistoricalPrice.builder()
                .symbol(symbol)
                .tradeDate(candle.getDateTime().toLocalDate())
                .openPrice(candle.getOpen())
                .highPrice(candle.getHigh())
                .lowPrice(candle.getLow())
                .closePrice(candle.getClose())
                .volume(candle.getVolume())
                .openInterest(candle.getOpenInterest())
                .exchange("NSE")
                .intervalType(CandleInterval.DAY)
                .createdAt(LocalDateTime.now())
                .build();

    }
}