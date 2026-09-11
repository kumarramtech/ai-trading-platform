package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.market.OpenPosition;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.exit.ExitOrchestrator;
import com.ram.trading.signal.engine.exit.TradeExitService;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.service.PaperTradingService;
import com.ram.trading.signal.engine.service.TrailingStopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExitServiceImpl implements TradeExitService {

    private final PaperTradeRepository paperTradeRepository;
    private final ExitOrchestrator exitOrchestrator;
    private final PaperTradingService paperTradingService;

    private final TrailingStopService trailingStopService;

    @Override
    public Mono<Void> evaluateExit(Tick tick) {

        log.debug("==========================================");
        log.debug("TRADE EXIT EVALUATION STARTED");
        log.debug("Symbol : {}", tick.getSymbol());
        log.debug("Current Price : {}", tick.getLastTradedPrice());
        log.debug("==========================================");

        PaperTrade trade = paperTradeRepository
                .findTopBySymbolAndStatusOrderByEntryTimeDesc(
                        tick.getSymbol(),
                        SignalStatus.OPEN)
                .orElse(null);

        if (trade == null) {

            log.debug(
                    "No OPEN Trade found for {}",
                    tick.getSymbol());

            return Mono.empty();
        }

        log.debug("OPEN Trade Found");
        log.debug("Entry Price : {}", trade.getEntryPrice());
        log.debug("Target Price : {}", trade.getTargetPrice());
        log.debug("Stop Loss : {}", trade.getStopLoss());
        log.debug("Quantity : {}", trade.getQuantity());

        return trailingStopService
                .updateTrailingStop(trade, tick)

                .flatMap(updatedTrade -> {
                    OpenPosition position = map(updatedTrade);
                    log.debug(
                            "EXIT EVALUATION STATE | Symbol={} | Price={} | Entry={} | " +
                                    "InitialStop={} | CurrentStop={} | TrailingStep={}",
                            updatedTrade.getSymbol(),
                            tick.getLastTradedPrice(),
                            updatedTrade.getEntryPrice(),
                            updatedTrade.getInitialStopLoss(),
                            updatedTrade.getCurrentStopLoss(),
                            updatedTrade.getTrailingStep());

                    ExitDecision decision =
                            exitOrchestrator.evaluate(
                                    position,
                                    tick);

                    log.debug(
                            "Exit Decision : {}",
                            decision);

                    if (!decision.isExit()) {
                        log.debug("Trade should continue.");
                        return Mono.empty();
                    }

                    /*
                     * ========================================================
                     * FINAL EXIT SNAPSHOT
                     * ========================================================
                     */

                    double entryPrice =
                            updatedTrade.getEntryPrice();

                    double exitPrice =
                            tick.getLastTradedPrice();

                    double profitLoss;

                    if (SignalType.SELL.name()
                            .equalsIgnoreCase(
                                    updatedTrade.getSignal())) {

                        profitLoss =
                                (entryPrice - exitPrice)
                                        * updatedTrade.getQuantity();

                    } else {

                        profitLoss =
                                (exitPrice - entryPrice)
                                        * updatedTrade.getQuantity();
                    }

                    long holdingSeconds = 0;

                    if (updatedTrade.getEntryTime() != null) {

                        holdingSeconds =
                                java.time.Duration.between(
                                                updatedTrade.getEntryTime(),
                                                LocalDateTime.now())
                                        .getSeconds();
                    }

                    log.info(
                            "TRADE_EXIT_SNAPSHOT | " +
                                    "TradeId={} | " +
                                    "Symbol={} | " +
                                    "Signal={} | " +
                                    "Entry={} | " +
                                    "Exit={} | " +
                                    "Target={} | " +
                                    "InitialStop={} | " +
                                    "CurrentStop={} | " +
                                    "Qty={} | " +
                                    "Reason={} | " +
                                    "PnL={} | " +
                                    "HoldingSeconds={} | " +
                                    "ExitTime={}",
                            updatedTrade.getId(),
                            updatedTrade.getSymbol(),
                            updatedTrade.getSignal(),
                            entryPrice,
                            exitPrice,
                            updatedTrade.getTargetPrice(),
                            updatedTrade.getInitialStopLoss(),
                            updatedTrade.getCurrentStopLoss(),
                            updatedTrade.getQuantity(),
                            decision.getReason(),
                            profitLoss,
                            holdingSeconds,
                            LocalDateTime.now());

                    return paperTradingService.closeTrade(
                            updatedTrade,
                            decision,
                            tick);
                });
    }

    private OpenPosition map(PaperTrade trade) {

        return OpenPosition.builder()
                .symbol(trade.getSymbol())
                .signal(trade.getSignal())
                .entryPrice(trade.getEntryPrice())
                .targetPrice(trade.getTargetPrice())
                .stopLoss(trade.getCurrentStopLoss())
                .initialStopLoss(trade.getInitialStopLoss())
                .quantity(trade.getQuantity())
                .build();
    }
}