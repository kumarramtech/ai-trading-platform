package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.watchlist.TrendingStockResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MiddayCandidateStore {

    private List<TrendingStockResponse> candidates =
            new ArrayList<>();

    private LocalDateTime discoveredAt;

    public synchronized void store(
            List<TrendingStockResponse> candidates) {

        this.candidates = candidates == null
                ? new ArrayList<>()
                : new ArrayList<>(candidates);

        this.discoveredAt = LocalDateTime.now();
    }

    public synchronized List<TrendingStockResponse> getCandidates() {
        return new ArrayList<>(candidates);
    }

    public synchronized LocalDateTime getDiscoveredAt() {
        return discoveredAt;
    }

    public synchronized void clear() {
        candidates.clear();
        discoveredAt = null;
    }
}