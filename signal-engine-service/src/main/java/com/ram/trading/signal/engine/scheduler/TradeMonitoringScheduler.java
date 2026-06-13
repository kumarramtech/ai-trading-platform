package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.service.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeMonitoringScheduler {
    
    private final PaperTradingService paperTradingService;

    @Scheduled(fixedDelay = 60000)
    public void monitorOpenTrades() {

        paperTradingService
                .monitorOpenTrades()
                .subscribe();
    }
}