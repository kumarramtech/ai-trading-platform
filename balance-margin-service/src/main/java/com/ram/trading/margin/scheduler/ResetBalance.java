package com.ram.trading.margin.scheduler;

import com.ram.trading.margin.service.PaperTradingAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class ResetBalance {

    private final PaperTradingAccountService paperTradingAccountService;

    @Scheduled(
            cron = "0 30 8 * * MON-FRI",
            zone = "Asia/Kolkata")
    public void resetPaperTradingBalance() {

        log.info("========================================");
        log.info("RESETTING PAPER TRADING BALANCE");
        log.info("========================================");

        paperTradingAccountService
                .resetAccount()
                .subscribe(
                        unused -> { },
                        error -> log.error(
                                "Failed to reset paper trading balance",
                                error),
                        () -> log.info(
                                "Paper trading balance reset successfully"));
    }
}
