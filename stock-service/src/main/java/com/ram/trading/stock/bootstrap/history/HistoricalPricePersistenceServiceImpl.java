package com.ram.trading.stock.bootstrap.history;

import com.ram.trading.stock.bootstrap.CandleInterval;
import com.ram.trading.stock.bootstrap.entity.HistoricalPrice;
import com.ram.trading.stock.dto.history.Candle;
import com.ram.trading.stock.dto.history.HistoricalCandleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

        if (response == null || response.getCandles() == null || response.getCandles().isEmpty()) {
            log.warn("No historical candles received for {}", response != null ? response.getSymbol() : "UNKNOWN");
            return;
        }

        log.info("Persisting {} candles for {}",
                response.getCandles().size(),
                response.getSymbol());

        List<HistoricalPrice> prices =
                response.getCandles()
                        .stream()
                        .map(candle -> mapToEntity(candle,response.getSymbol()))
                        .toList();

        historicalPriceService.deleteBySymbol(response.getSymbol());

        historicalPriceService.flush();

        historicalPriceService.saveAll(prices);

        log.info("Successfully persisted {} historical candles for {}",
                prices.size(),
                response.getSymbol());
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