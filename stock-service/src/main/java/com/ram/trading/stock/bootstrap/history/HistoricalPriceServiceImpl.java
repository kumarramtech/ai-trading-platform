package com.ram.trading.stock.bootstrap.history;

import com.ram.trading.stock.bootstrap.entity.HistoricalPrice;
import com.ram.trading.stock.bootstrap.repo.HistoricalPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HistoricalPriceServiceImpl implements HistoricalPriceService {

    private final HistoricalPriceRepository repository;

    @Override
    public void saveAll(List<HistoricalPrice> prices) {

        Map<String, HistoricalPrice> unique = new LinkedHashMap<>();

        for (HistoricalPrice hp : prices) {

            String key =
                    hp.getSymbol() + "|" +
                            hp.getTradeDate() + "|" +
                            hp.getIntervalType();

            unique.putIfAbsent(key, hp);
        }

        repository.saveAll(unique.values());

    }

    @Override
    public List<HistoricalPrice> findBySymbol(String symbol) {

        return repository.findBySymbolOrderByTradeDateAsc(symbol);

    }

    @Override
    public void deleteBySymbol(String symbol) {

        repository.deleteBySymbol(symbol);

    }

    @Override
    public List<HistoricalPrice> findBySymbolAndDateRange(
            String symbol,
            LocalDate from,
            LocalDate to) {

        return repository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
                        symbol,
                        from,
                        to);

    }

    @Override
    public List<HistoricalPrice> findLatest(
            String symbol,
            Integer limit) {

        return repository
                .findTop100BySymbolOrderByTradeDateDesc(
                        symbol);

    }

    @Override
    public void flush() {
        repository.flush();
    }

}