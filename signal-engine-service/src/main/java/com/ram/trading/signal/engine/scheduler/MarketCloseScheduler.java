package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.service.MarketCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketCloseScheduler {

    private final MarketCloseService marketCloseService;

    @Scheduled(cron = "0 20-29 15 * * MON-FRI")
    public void autoSquareOff() {
        marketCloseService.closeOpenPositions().subscribe();
    }

}