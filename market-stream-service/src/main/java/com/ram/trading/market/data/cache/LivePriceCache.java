package com.ram.trading.market.data.cache;

import com.ram.trading.market.data.dto.LivePrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LivePriceCache {

    private final ConcurrentHashMap<String, LivePrice> cache = new ConcurrentHashMap<>();

    public void update(LivePrice livePrice) {

        cache.put(livePrice.getSymbol(), livePrice);

        log.debug("Updated Live Price : {} -> {}",
                livePrice.getSymbol(),
                livePrice.getPrice());
    }

    public Optional<LivePrice> findBySymbol(String symbol) {
        return Optional.ofNullable(cache.get(symbol));
    }

    public List<LivePrice> findAll() {
        return new ArrayList<>(cache.values());
    }

    public boolean exists(String symbol) {
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