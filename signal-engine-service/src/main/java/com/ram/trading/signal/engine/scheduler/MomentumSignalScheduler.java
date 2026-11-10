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
 * first successful run of the month wins. The list has no holiday calendar (on a holiday
 * it arrives the day before the orders can be placed); the paper rebalance, top-up and
 * snapshot skip days when NIFTY did not trade.
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

    private com.ram.trading.signal.engine.calendar.TradingCalendar calendar;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCalendar(com.ram.trading.signal.engine.calendar.TradingCalendar calendar) {
        this.calendar = calendar;
    }

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
            if (marketClosed("rebalance")) {
                return;
            }
            momentumPaperService.rebalance();
        } catch (Exception ex) {
            log.error("Paper momentum rebalance failed", ex);
        }
    }

    /** 09:25 weekdays: idle cash left by whole shares goes to this month's list (no-op below 5% of equity). */
    @Scheduled(cron = "${trading.momentum.paper.fill-cron:0 25 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fillIdleCash() {
        if (!enabled) {
            return;
        }
        try {
            if (marketClosed("idle cash top-up")) {
                return;
            }
            momentumPaperService.fillIdleCash();
        } catch (Exception ex) {
            log.error("Paper momentum idle cash top-up failed", ex);
        }
    }

    /**
     * Daily paper equity at the close, for the return and drawdown against NIFTY, and the
     * daily Slack summary (trading.momentum.paper.daily-summary; skipped for an all-cash book).
     */
    @Scheduled(cron = "${trading.momentum.paper.snapshot-cron:0 40 15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void snapshotPaperPortfolio() {
        if (!enabled) {
            return;
        }
        try {
            if (marketClosed("snapshot")) {
                return;
            }
            momentumPaperService.snapshot();
        } catch (Exception ex) {
            log.error("Paper momentum snapshot failed", ex);
        }
    }

    /**
     * Market holiday guard: no NIFTY minute candle today means the exchange did not trade
     * (the feed only repeats the last day's prices), so paper buys and the snapshot wait for
     * the next trading day. A rebalance skipped on a holiday runs on the next weekday (days 1-7).
     */
    private boolean marketClosed(String job) {
        if (calendar != null && !calendar.isTradingDay()) {
            log.info("Paper momentum {} skipped: market closed ({})", job, calendar.reason());
            return true;
        }
        if (momentumPaperService.marketTradedToday()) {
            return false;
        }
        log.info("Paper momentum {} skipped: no NIFTY candles today (market holiday or no data)", job);
        return true;
    }
}
