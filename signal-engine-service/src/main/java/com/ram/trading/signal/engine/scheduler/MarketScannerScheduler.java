package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.service.MarketScannerService;
import com.ram.trading.signal.engine.util.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketScannerScheduler {

    private final MarketScannerService marketScannerService;

    private final TradingSessionService tradingSessionService;


    @Scheduled(fixedRate = 300000)
    public void scanMarket() {

        if (!tradingSessionService.canCreateTrade()) {
            return;
        }

        log.info("Starting Market Scan");

        marketScannerService.scanMarket();
    }
}