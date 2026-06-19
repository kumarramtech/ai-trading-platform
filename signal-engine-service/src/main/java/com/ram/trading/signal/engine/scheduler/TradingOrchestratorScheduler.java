package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.service.TradingOrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingOrchestratorScheduler {

    private final TradingOrchestratorService service;

    @Scheduled(fixedDelay = 300000)
    public void runCycle() {
        log.info("Starting scheduled trading cycle");
        service.runTradingCycle().subscribe();
    }
}