package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.premarket.PreMarketCandidate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class PreMarketCandidateStore {

    private final List<PreMarketCandidate> candidates =
            new ArrayList<>();

    private LocalDateTime discoveredAt;


    public synchronized void store(
            List<PreMarketCandidate> newCandidates) {

        candidates.clear();

        if (newCandidates != null) {
            candidates.addAll(newCandidates);
        }

        discoveredAt = LocalDateTime.now();
    }


    public synchronized List<PreMarketCandidate> getCandidates() {

        return Collections.unmodifiableList(
                new ArrayList<>(candidates)
        );
    }


    public synchronized LocalDateTime getDiscoveredAt() {

        return discoveredAt;
    }


    public synchronized boolean isValidForToday() {

        return discoveredAt != null
                && discoveredAt.toLocalDate()
                .equals(LocalDate.now());
    }


    public synchronized int size() {

        return candidates.size();
    }


    public synchronized void clear() {

        candidates.clear();
        discoveredAt = null;
    }
}