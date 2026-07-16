package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.dto.market.Tick;
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

        // implementation

        return null;
    }
}