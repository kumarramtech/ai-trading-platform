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

        log.info("==========================================");
        log.info("TRADE EXIT EVALUATION STARTED");
        log.info("Symbol : {}", tick.getSymbol());
        log.info("Current Price : {}", tick.getLastTradedPrice());
        log.info("==========================================");

        PaperTrade trade = paperTradeRepository
                .findTopBySymbolAndStatusOrderByEntryTimeDesc(
                        tick.getSymbol(),
                        SignalStatus.OPEN)
                .orElse(null);

        if (trade == null) {

            log.info(
                    "No OPEN Trade found for {}",
                    tick.getSymbol());

            return Mono.empty();
        }

        log.info("OPEN Trade Found");
        log.info("Entry Price : {}", trade.getEntryPrice());
        log.info("Target Price : {}", trade.getTargetPrice());
        log.info("Stop Loss : {}", trade.getStopLoss());
        log.info("Quantity : {}", trade.getQuantity());

        return trailingStopService
                .updateTrailingStop(trade, tick)

                .flatMap(updatedTrade -> {

                    OpenPosition position =
                            map(updatedTrade);

                    ExitDecision decision =
                            exitOrchestrator.evaluate(
                                    position,
                                    tick);

                    log.info(
                            "Exit Decision : {}",
                            decision);

                    if (!decision.isExit()) {

                        log.info(
                                "Trade should continue.");

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