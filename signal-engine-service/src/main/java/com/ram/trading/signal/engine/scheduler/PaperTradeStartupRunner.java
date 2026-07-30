package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.service.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaperTradeStartupRunner implements ApplicationRunner {

    private final PaperTradingService paperTradingService;

    @Override
    public void run(ApplicationArguments args) {

        paperTradingService
                .closePreviousDayTrades()
                .block();

        log.info("Paper Trade Startup Cleanup Completed");
    }
}