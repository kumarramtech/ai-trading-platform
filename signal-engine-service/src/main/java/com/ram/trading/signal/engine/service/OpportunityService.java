package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.Opportunity;
import com.ram.trading.signal.engine.repo.OpportunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpportunityService {

    private final OpportunityRepository repository;

    public Opportunity save(
            TradingSignal signal) {

        Opportunity opportunity =
                Opportunity.builder()
                        .symbol(signal.getSymbol())
                        .opportunitySignal(signal.getSignal())
                        .confidence(signal.getConfidence())
                        .newsScore(signal.getNewsScore())
                        .sentiment(signal.getNewsSentiment())
                        .selected(false)
                        .createdAt(LocalDateTime.now())
                        .build();

        return repository.save(opportunity);
    }

    public List<Opportunity> getAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public List<Opportunity> getTopOpportunities() {

        return latestOpportunities()
                .stream()
                .sorted(
                        Comparator.comparing(
                                        Opportunity::getConfidence)
                                .reversed())
                .limit(3)
                .toList();
    }
    public List<Opportunity> latestOpportunities() {

            Map<String, Opportunity> latest =
                    repository.findAllByOrderByCreatedAtDesc()
                            .stream()
                            .collect(Collectors.toMap(
                                    Opportunity::getSymbol,
                                    Function.identity(),
                                    (first, second) -> first,
                                    LinkedHashMap::new));

            return new ArrayList<>(latest.values());
        }


}