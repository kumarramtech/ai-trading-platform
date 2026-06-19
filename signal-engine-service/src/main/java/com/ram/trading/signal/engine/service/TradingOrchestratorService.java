package com.ram.trading.signal.engine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingOrchestratorService {

    private final OpportunityService opportunityService;

    public Mono<String> runTradingCycle() {

        log.info("========== TRADING CYCLE STARTED ==========");

        return Mono.fromRunnable(() -> {

                    opportunityService.markTopOpportunities();

                    log.info("Top opportunities selected");
                })

                .then(opportunityService.executeSelectedOpportunities())

                .thenReturn("Trading Cycle Completed")

                .doOnSuccess(result ->
                        log.info("========== TRADING CYCLE COMPLETED =========="))

                .doOnError(error ->
                        log.error("Trading Cycle Failed", error));
    }
}