package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.market.OpenPosition;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.exit.ExitOrchestrator;
import com.ram.trading.signal.engine.exit.TradeExitService;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.service.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExitServiceImpl implements TradeExitService {

    private final PaperTradeRepository paperTradeRepository;
    private final ExitOrchestrator exitOrchestrator;
    private final PaperTradingService paperTradingService;

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

            log.info("No OPEN Trade found for {}", tick.getSymbol());

            return Mono.empty();
        }

        log.info("OPEN Trade Found");
        log.info("Entry Price : {}", trade.getEntryPrice());
        log.info("Target Price : {}", trade.getTargetPrice());
        log.info("Stop Loss : {}", trade.getStopLoss());
        log.info("Quantity : {}", trade.getQuantity());

        OpenPosition position = map(trade);

        log.info("Calling Exit Orchestrator...");

        ExitDecision decision = exitOrchestrator.evaluate(position, tick);

        log.info("Exit Decision : {}", decision);

        if (!decision.isExit()) {

            log.info("Trade should continue. No Exit.");

            return Mono.empty();
        }

        log.info("==========================================");
        log.info("EXIT TRIGGERED");
        log.info("Reason : {}", decision.getReason());
        log.info("Exit Price : {}", tick.getLastTradedPrice());
        log.info("==========================================");

        return paperTradingService.closeTrade(
                trade,
                decision,
                tick);
    }

    private OpenPosition map(PaperTrade trade) {

        return OpenPosition.builder()
                .symbol(trade.getSymbol())
                .signal(trade.getSignal())
                .entryPrice(trade.getEntryPrice())
                .targetPrice(trade.getTargetPrice())
                .stopLoss(trade.getStopLoss())
                .quantity(trade.getQuantity())
                .build();
    }
}