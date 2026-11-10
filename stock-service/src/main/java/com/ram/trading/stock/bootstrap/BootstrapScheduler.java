package com.ram.trading.stock.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        value = "bootstrap.enabled", havingValue = "true")
public class BootstrapScheduler {

    private final BootstrapService bootstrapService;

    private final com.ram.trading.stock.service.calendar.MarketCalendarService calendar;

    @Scheduled(cron = "${bootstrap.cron}", zone = "Asia/Kolkata")
    public void bootstrap() {
        var day = calendar.today();
        if (!day.tradingDay()) {
            log.info("Bootstrap skipped: {} ({})", day.date(), day.reason());
            return;
        }
        bootstrapService.bootstrap();

    }
}