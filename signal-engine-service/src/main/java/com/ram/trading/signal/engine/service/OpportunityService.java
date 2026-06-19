package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradeLifecycleDto;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.Opportunity;
import com.ram.trading.signal.engine.entity.PaperTrade;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

        // Reset old selections
        opportunityRepository.resetSelections();

        List<Opportunity> opportunities =
                getTopOpportunities();

        opportunities.forEach(opportunity -> {
            opportunity.setSelected(true);
            opportunityRepository.save(opportunity);
        });

        log.info("Selected Top Opportunities={}",
                opportunities.size());
    }

    public Mono<Void> executeSelectedOpportunities() {

        List<Opportunity> opportunities =
                opportunityRepository.findBySelectedTrue();
        log.info("Executing {} selected opportunities", opportunities.size());
        return Flux.fromIterable(opportunities)

                .filter(opportunity -> {

                    boolean exists =
                            paperTradeRepository.existsBySymbolAndStatus(
                                    opportunity.getSymbol(),
                                    SignalStatus.OPEN);

                    if (exists) {

                        log.info(
                                "Skipping {} - OPEN trade already exists",
                                opportunity.getSymbol());

                    } else {

                        log.info(
                                "Creating trade for {}",
                                opportunity.getSymbol());
                    }

                    return !exists;
                })

                .filter(opportunity ->
                        opportunity.getSignalId() != null)

                .flatMap(opportunity -> {

                    TradingSignalEntity savedSignal =
                            tradingSignalService.findById(
                                    opportunity.getSignalId());

                    if (savedSignal == null) {

                        log.warn(
                                "Signal not found for opportunity {}",
                                opportunity.getId());

                        return Mono.empty();
                    }

                    return indicatorClient
                            .getLatest(opportunity.getSymbol())
                            .doOnNext(indicator ->
                                    paperTradingService.createTrade(
                                            savedSignal,
                                            indicator));
                })

                .then();
    }

    public List<TradeLifecycleDto> getTradeLifecycle() {

        List<PaperTrade> trades =
                paperTradeRepository.findAll();

        return trades.stream()
                .sorted(
                        Comparator.comparing(
                                        PaperTrade::getEntryTime)
                                .reversed())
                .map(this::mapTradeLifecycle)
                .toList();
    }

    private TradeLifecycleDto mapTradeLifecycle(
            PaperTrade trade) {

        Opportunity opportunity =
                opportunityRepository
                        .findBySignalId(
                                trade.getSignalId())
                        .orElse(null);

        return TradeLifecycleDto.builder()

                .opportunityId(
                        opportunity != null
                                ? opportunity.getId()
                                : null)

                .signalId(trade.getSignalId())

                .tradeId(trade.getId())

                .symbol(trade.getSymbol())

                .signal(trade.getSignal())

                .confidence(
                        opportunity != null
                                ? opportunity.getConfidence()
                                : null)

                .newsScore(opportunity != null ? opportunity.getNewsScore(): null)

                .sentiment(
                        opportunity != null
                                ? opportunity.getSentiment()
                                : null)

                .entryPrice(trade.getEntryPrice())

                .targetPrice(trade.getTargetPrice())

                .stopLoss(trade.getStopLoss())

                .quantity(trade.getQuantity())

                .investedAmount(
                        trade.getInvestedAmount())

                .tradeStatus(
                        trade.getStatus().name())

                .profitLoss(
                        trade.getProfitLoss())

                .entryTime(
                        trade.getEntryTime())

                .exitTime(
                        trade.getExitTime())

                .build();
    }


}