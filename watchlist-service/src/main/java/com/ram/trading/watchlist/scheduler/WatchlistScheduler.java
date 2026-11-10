package com.ram.trading.watchlist.scheduler;

import com.ram.trading.watchlist.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WatchlistScheduler {

    private final WatchlistService watchlistService;

    private final com.ram.trading.watchlist.calendar.TradingCalendar calendar;

    @Scheduled(
            cron = "${watchlist.scheduler.cron}",
            zone = "Asia/Kolkata"
    )
    public void generateDailyWatchlist() {
        if (!calendar.isTradingDay()) {
            log.info("Scheduled watchlist generation skipped: market closed ({})", calendar.reason());
            return;
        }

        log.info("==============================================");
        log.info("SCHEDULED WATCHLIST GENERATION STARTED");
        log.info("==============================================");

        watchlistService.generateWatchlist()
                .doOnSuccess(response -> {
                    log.info(
                            "Scheduled watchlist generation completed | Candidates={}",
                            response != null
                                    ? response.getTechnicalCandidates()
                                    : 0
                    );
                })
                .doOnError(ex -> {
                    log.error(
                            "Scheduled watchlist generation failed",
                            ex
                    );
                })
                .subscribe();
    }
}