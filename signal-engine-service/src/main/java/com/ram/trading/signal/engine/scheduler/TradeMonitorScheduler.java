package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.service.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TradeMonitorScheduler {

    private final PaperTradingService paperTradingService;

    @Scheduled(fixedRate = 60000)
    public void monitorTrades() {

        log.info("Starting Trade Monitoring");

        paperTradingService.monitorOpenTrades()
                .subscribe(
                        null,
                        error -> log.error(
                                "Trade monitor failed",
                                error),
                        () -> log.info(
                                "Trade Monitoring Completed"));
    }
}