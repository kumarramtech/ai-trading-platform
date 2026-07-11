package com.ram.trading.market.data.cache;

import com.ram.trading.market.data.dto.MarketInstrument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InstrumentCache {


    private final ConcurrentHashMap<String, MarketInstrument> cache =
            new ConcurrentHashMap<>();

    public void put(MarketInstrument instrument) {

        cache.put(instrument.getSymbol(), instrument);

    }

    public Optional<MarketInstrument> findBySymbol(String symbol) {

        return Optional.ofNullable(cache.get(symbol));

    }

    public List<MarketInstrument> findAll() {

        return new ArrayList<>(cache.values());

    }

    public boolean contains(String symbol) {

        return cache.containsKey(symbol);

    }

    public void remove(String symbol) {

        cache.remove(symbol);

    }

    public void clear() {

        cache.clear();

    }

    public int size() {

        return cache.size();

    }

}