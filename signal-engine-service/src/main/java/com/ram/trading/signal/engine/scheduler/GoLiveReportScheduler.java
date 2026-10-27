package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.service.performance.GoLiveReadinessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Weekly go-live readiness report: logged and sent to Slack at 15:45 on Fridays,
 * after the daily summary and before the instance is stopped for the night.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoLiveReportScheduler {

    private final GoLiveReadinessService goLiveReadinessService;

    private final NotificationClient notificationClient;

    @Scheduled(
            cron = "${trading.go-live.report-cron:0 45 15 * * FRI}",
            zone = "Asia/Kolkata"
    )
    public void sendWeeklyReport() {

        try {
            GoLiveReadinessService.ReadinessReport report =
                    goLiveReadinessService.report(LocalDate.now(ZoneId.of("Asia/Kolkata")));

            String text = goLiveReadinessService.toText(report);

            log.info("WEEKLY GO-LIVE REPORT\n{}", text);

            notificationClient
                    .sendNotification(NotificationRequest.builder()
                            .channel(NotificationChannel.SLACK)
                            .title(report.readyForLive()
                                    ? "Weekly go-live report: READY"
                                    : "Weekly go-live report: not ready yet")
                            .message(text)
                            .build())
                    .doOnError(ex -> log.error("Weekly go-live report: Slack send failed", ex))
                    .onErrorResume(ex -> reactor.core.publisher.Mono.empty())
                    .subscribe();

        } catch (Exception ex) {
            log.error("Weekly go-live report failed", ex);
        }
    }
}
