package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.service.replay.MomentumSignalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monthly momentum list to Slack before the open on the first weekday of the month.
 * The cron fires on weekdays 1-7 of each month (twice, the second as a retry when
 * the broker token was not ready); a month already published is skipped, so the
 * first successful run of the month wins. No market-holiday calendar: on a holiday
 * the list arrives the day before the orders can be placed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MomentumSignalScheduler {

    private final MomentumSignalService momentumSignalService;

    private final com.ram.trading.signal.engine.service.replay.MomentumPaperService momentumPaperService;

    private final NotificationClient notificationClient;

    @Value("${trading.momentum.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${trading.momentum.cron:0 50 8,10 1-7 * MON-FRI}", zone = "Asia/Kolkata")
    public void publishMonthlyList() {
        if (!enabled) {
            return;
        }
        try {
            momentumSignalService.publish(false)
                    .ifPresent(s -> log.info("Monthly momentum list {} published: {} stocks", s.month(), s.target().size()));
        } catch (Exception ex) {
            log.error("Monthly momentum list failed", ex);
            notificationClient.sendNotification(NotificationRequest.builder()
                            .channel(NotificationChannel.SLACK)
                            .title("Monthly momentum list FAILED")
                            .message(ex.getMessage() + "\nRetry: POST /analytics/momentum-signal/publish")
                            .build())
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .subscribe();
        }
    }

    /** Paper fills for the month's list shortly after the open (11:05 after a 10:50 list). */
    @Scheduled(cron = "${trading.momentum.paper.rebalance-cron:0 20 9,11 1-7 * MON-FRI}", zone = "Asia/Kolkata")
    public void rebalancePaperPortfolio() {
        if (!enabled) {
            return;
        }
        try {
            momentumPaperService.rebalance();
        } catch (Exception ex) {
            log.error("Paper momentum rebalance failed", ex);
        }
    }

    /** Daily paper equity at the close, for the return and drawdown against NIFTY. */
    @Scheduled(cron = "${trading.momentum.paper.snapshot-cron:0 40 15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void snapshotPaperPortfolio() {
        if (!enabled) {
            return;
        }
        try {
            momentumPaperService.snapshot();
        } catch (Exception ex) {
            log.error("Paper momentum snapshot failed", ex);
        }
    }
}
