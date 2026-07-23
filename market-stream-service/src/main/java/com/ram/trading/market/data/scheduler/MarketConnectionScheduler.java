package com.ram.trading.market.data.scheduler;


import com.ram.trading.market.data.service.MarketDataProvider;
import com.ram.trading.market.data.service.MarketSessionService;
import com.ram.trading.market.data.service.MarketStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketConnectionScheduler {

    private final MarketStreamService marketStreamService;
    private final MarketSessionService marketSessionService;

    /**
     * Every minute.
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void manageConnection() {

        if (marketSessionService.isMarketOpen()) {
            marketStreamService.start();
        } else {
            marketStreamService.stop();
        }
    }
}