package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.TradeLifecycleDto;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.Opportunity;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import com.ram.trading.signal.engine.repo.OpportunityRepository;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.risk.PositionSizingResult;
import com.ram.trading.signal.engine.risk.PositionSizingService;
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
    private final TechnicalIndicatorService technicalIndicatorService;
    private final PositionSizingService positionSizingService;

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

        log.info("Starting execution of selected opportunities");

        /*
         * ============================================================
         * STEP 1 : GET SELECTED OPPORTUNITIES
         * ============================================================
         */
        List<Opportunity> opportunities =
                opportunityRepository.findBySelectedTrue();

        if (opportunities == null || opportunities.isEmpty()) {

            log.info("No selected opportunities found");

            return Mono.empty();
        }

        /*
         * ============================================================
         * STEP 2 : PROCESS EACH OPPORTUNITY
         * ============================================================
         */
        for (Opportunity opportunity : opportunities) {

            /*
             * --------------------------------------------------------
             * Validate Signal ID
             * --------------------------------------------------------
             */
            if (opportunity.getSignalId() == null) {

                log.warn(
                        "OPPORTUNITY EXECUTION SKIPPED | " +
                                "Symbol={} | Reason=SignalId is null",
                        opportunity.getSymbol());

                continue;
            }

            String symbol =
                    opportunity.getSymbol();

            Long signalId =
                    opportunity.getSignalId();

            log.info(
                    "Executing selected opportunity | " +
                            "Symbol={} | SignalId={}",
                    symbol,
                    signalId);

            /*
             * ========================================================
             * STEP 3 : CHECK EXISTING OPEN PAPER TRADE
             * ========================================================
             */
            boolean openTradeExists =
                    paperTradeRepository
                            .findTopBySymbolAndStatusOrderByEntryTimeDesc(
                                    symbol,
                                    SignalStatus.OPEN)
                            .isPresent();

            if (openTradeExists) {

                log.warn(
                        "OPPORTUNITY EXECUTION SKIPPED | " +
                                "Symbol={} | Reason=Open trade already exists",
                        symbol);

                continue;
            }

            /*
             * ========================================================
             * STEP 4 : LOAD TRADING SIGNAL
             * ========================================================
             *
             * IMPORTANT:
             *
             * findById() returns TradingSignalEntity directly.
             * It does NOT return Mono<TradingSignalEntity>.
             *
             * Therefore DO NOT use flatMap() here.
             */
            TradingSignalEntity savedSignal =
                    tradingSignalService.findById(signalId);

            if (savedSignal == null) {

                log.warn(
                        "OPPORTUNITY EXECUTION SKIPPED | " +
                                "Symbol={} | SignalId={} | " +
                                "Reason=Trading signal not found",
                        symbol,
                        signalId);

                continue;
            }

            /*
             * ========================================================
             * STEP 5 : CALCULATE TECHNICAL INDICATORS
             * ========================================================
             */
            technicalIndicatorService
                    .calculate(symbol)

                    .doOnNext(indicator -> {

                        /*
                         * ====================================================
                         * STEP 6 : POSITION SIZING
                         * ====================================================
                         *
                         * Position sizing is based on the broker's actual
                         * margin requirement and the configured target
                         * margin per trade.
                         *
                         * IMPORTANT:
                         * - ₹10,000 target margin determines entry size.
                         * - Actual broker leverage is used.
                         * - ₹250 max loss is an EXIT trigger only.
                         * - ₹250 must NOT reduce entry quantity.
                         */
                        positionSizingService
                                .calculate(convertToTradingSignal(savedSignal))
                                .subscribe(
                                        sizingResult -> {

                                            /*
                                             * ------------------------------------------------
                                             * Position sizing validation
                                             * ------------------------------------------------
                                             */
                                            if (!sizingResult.isValid()) {

                                                log.warn(
                                                        "POSITION SIZING REJECTED | " +
                                                                "Symbol={} | Reason={}",
                                                        savedSignal.getSymbol(),
                                                        sizingResult.getRejectionReason());

                                                return;
                                            }

                                            /*
                                             * ------------------------------------------------
                                             * Get broker-margin based quantity
                                             * ------------------------------------------------
                                             */
                                            int approvedQuantity =
                                                    sizingResult.getMarginBasedQuantity();

                                            if (approvedQuantity <= 0) {

                                                log.warn(
                                                        "POSITION SIZING REJECTED | " +
                                                                "Symbol={} | MarginQuantity={}",
                                                        savedSignal.getSymbol(),
                                                        approvedQuantity);

                                                return;
                                            }

                                            /*
                                             * ====================================================
                                             * POSITION SIZING LOG
                                             * ====================================================
                                             */
                                            log.info(
                                                    "OPPORTUNITY EXECUTION POSITION SIZING | " +
                                                            "Symbol={} | Entry={} | StopLoss={} | " +
                                                            "TargetMargin={} | RequiredMargin={} | " +
                                                            "TradeValue={} | Leverage={} | " +
                                                            "MarginQuantity={} | TechnicalStopRisk={} | " +
                                                            "MaxLossTrigger={}",

                                                    savedSignal.getSymbol(),

                                                    savedSignal.getEntryPrice(),

                                                    savedSignal.getStopLoss(),

                                                    sizingResult.getTargetMargin(),

                                                    sizingResult.getRequiredMargin(),

                                                    sizingResult.getTradeValue(),

                                                    sizingResult.getLeverage(),

                                                    approvedQuantity,

                                                    sizingResult.getExpectedRisk(),

                                                    sizingResult.getMaxRiskPerTrade());

                                            /*
                                             * ====================================================
                                             * STEP 7 : CREATE PAPER TRADE
                                             * ====================================================
                                             */
                                            paperTradingService.createTrade(
                                                    savedSignal,
                                                    indicator,
                                                    sizingResult);
                                        },
                                        error -> log.error(
                                                "POSITION SIZING FAILED | " +
                                                        "Symbol={} | SignalId={} | Error={}",
                                                savedSignal.getSymbol(),
                                                savedSignal.getId(),
                                                error.getMessage(),
                                                error));
                    })

                    /*
                     * ========================================================
                     * STEP 8 : ERROR HANDLING
                     * ========================================================
                     */
                    .doOnError(error ->
                            log.error(
                                    "OPPORTUNITY EXECUTION FAILED | " +
                                            "Symbol={} | SignalId={} | Error={}",
                                    symbol,
                                    signalId,
                                    error.getMessage(),
                                    error))

                    .onErrorResume(error -> Mono.empty())

                    .subscribe();
        }

        return Mono.empty();
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

    private TradingSignal convertToTradingSignal(
            TradingSignalEntity entity) {

        return TradingSignal.builder()
                .symbol(entity.getSymbol())
                .signal(entity.getSignal())
                .entryPrice(entity.getEntryPrice())
                .stopLoss(entity.getStopLoss())
                .targetPrice(entity.getTargetPrice())
                .confidence(entity.getConfidence())
                .build();
    }


}