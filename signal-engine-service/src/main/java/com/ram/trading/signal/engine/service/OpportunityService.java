package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.Opportunity;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.repo.OpportunityRepository;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpportunityService {

    private final OpportunityRepository opportunityRepository;
    private final TradingSignalService tradingSignalService;
    private final PaperTradingService paperTradingService;
    private final PaperTradeRepository paperTradeRepository;
    private final IndicatorClient indicatorClient;

    public Opportunity save(TradingSignal signal,Long signalId) {
        Opportunity opportunity =
                Opportunity.builder()
                        .symbol(signal.getSymbol())
                        .opportunitySignal(signal.getSignal())
                        .confidence(signal.getConfidence())
                        .newsScore(signal.getNewsScore())
                        .sentiment(signal.getNewsSentiment())
                        .selected(false)
                        .signalId(signalId)
                        .createdAt(LocalDateTime.now())
                        .build();

        return opportunityRepository.save(opportunity);
    }

    public List<Opportunity> getAll() {
        return opportunityRepository.findAllByOrderByCreatedAtDesc();
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
                    opportunityRepository.findAllByOrderByCreatedAtDesc()
                            .stream()
                            .collect(Collectors.toMap(
                                    Opportunity::getSymbol,
                                    Function.identity(),
                                    (first, second) -> first,
                                    LinkedHashMap::new));

            return new ArrayList<>(latest.values());
        }

    @Transactional
    public void markTopOpportunities() {

        List<Opportunity> opportunities =  getTopOpportunities();

        opportunities.forEach(opportunity -> {
            opportunity.setSelected(true);
            opportunityRepository.save(opportunity);
        });
    }

    public void executeSelectedOpportunities() {

        List<Opportunity> opportunities =
                opportunityRepository.findBySelectedTrue();

        opportunities.forEach(opportunity -> {

            boolean exists = paperTradeRepository.existsBySymbolAndStatus(opportunity.getSymbol(),SignalStatus.OPEN);
            if (exists) {
                return;
            }

            TradingSignalEntity savedSignal =
                    tradingSignalService.findById(opportunity.getSignalId());

            if(savedSignal == null){
                return;
            }

            TechnicalIndicatorResponse indicator =indicatorClient
                            .getLatest(opportunity.getSymbol())
                            .block();

            paperTradingService.createTrade(savedSignal,indicator);
        });
    }


}